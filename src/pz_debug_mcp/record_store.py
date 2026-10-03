"""Continuously archive game records locally, independently of model requests."""

from __future__ import annotations

import json
import sqlite3
import threading

from .bridge import Bridge


class RecordStore:
    def __init__(self, bridge: Bridge):
        self.bridge = bridge
        self.lock = threading.Lock()

    def connect(self, endpoint):
        directory = self.bridge.directory(endpoint)
        directory.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(directory / 'recordings.sqlite3', timeout=5)
        connection.executescript('''
            CREATE TABLE IF NOT EXISTS records (
                session TEXT, sequence INTEGER, target TEXT, kind TEXT, payload TEXT,
                PRIMARY KEY(session, sequence));
            CREATE TABLE IF NOT EXISTS sessions (
                session TEXT PRIMARY KEY, cursor INTEGER NOT NULL DEFAULT 0, observed INTEGER NOT NULL DEFAULT 0,
                reviewed INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS gaps (
                session TEXT, first INTEGER, last INTEGER, PRIMARY KEY(session, first, last));
            CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT);
        ''')
        columns = {row[1] for row in connection.execute('PRAGMA table_info(sessions)')}
        if 'reviewed' not in columns:
            connection.execute('ALTER TABLE sessions ADD COLUMN reviewed INTEGER NOT NULL DEFAULT 0')
            connection.commit()
        return connection

    def sync(self, endpoint):
        with self.lock:
            first = self.bridge.recorded(endpoint, limit=1)
            session = first.get('session')
            if not session:
                return
            connection = self.connect(endpoint)
            try:
                with connection:
                    connection.execute('INSERT OR IGNORE INTO sessions(session,reviewed) VALUES (?,1)', (session,))
                    connection.execute('UPDATE sessions SET reviewed=1 WHERE session=?', (session,))
                    connection.execute("INSERT OR REPLACE INTO metadata VALUES ('current_session', ?)", (session,))
                    cursor = connection.execute('SELECT cursor FROM sessions WHERE session=?', (session,)).fetchone()[0]
                    for _ in range(20):
                        batch = self.bridge.recorded(endpoint, after=cursor, limit=100, session=session)
                        if batch.get('session') != session:
                            break
                        for record in batch['records']:
                            sequence = record['sequence']
                            if sequence > cursor + 1:
                                connection.execute('INSERT OR IGNORE INTO gaps VALUES (?,?,?)', (session, cursor + 1, sequence - 1))
                            connection.execute('INSERT OR IGNORE INTO records VALUES (?,?,?,?,?)',
                                (session, sequence, record.get('target', ''), record.get('kind', ''),
                                 json.dumps(record, ensure_ascii=False, allow_nan=False)))
                            cursor = sequence
                        if not batch.get('warnings'):
                            if batch.get('cursor', cursor) > cursor:
                                connection.execute('INSERT OR IGNORE INTO gaps VALUES (?,?,?)', (session, cursor + 1, batch['cursor']))
                            cursor = max(cursor, batch.get('cursor', cursor))
                        connection.execute('UPDATE sessions SET cursor=?, observed=observed+1 WHERE session=?', (cursor, session))
                        if not batch.get('has_more') or batch.get('warnings'):
                            break
            finally:
                connection.close()

    def sync_all(self):
        for endpoint in ('client', 'server'):
            self.sync(endpoint)

    def read(self, endpoint, after=0, limit=50, target=None, kind=None, session=None, recording_session=None):
        self.sync(endpoint)
        with self.lock:
            path = self.bridge.directory(endpoint) / 'recordings.sqlite3'
            if not path.exists():
                return self.bridge.recorded(endpoint, after, limit, target, kind, session)
            connection = self.connect(endpoint)
            try:
                current = connection.execute("SELECT value FROM metadata WHERE key='current_session'").fetchone()
                selected = recording_session or (current[0] if current else None)
                sessions = [{'session': row[0], 'records': row[1], 'first': row[2], 'last': row[3]}
                    for row in connection.execute('SELECT r.session,COUNT(*),MIN(r.sequence),MAX(r.sequence) FROM records r JOIN sessions s ON r.session=s.session WHERE s.reviewed=1 GROUP BY r.session ORDER BY MAX(r.rowid) DESC LIMIT 50')]
                if not selected:
                    return {'records': [], 'session': None, 'cursor': after, 'missing': True, 'has_more': False, 'available_sessions': sessions}
                verified = connection.execute('SELECT reviewed FROM sessions WHERE session=?', (selected,)).fetchone()
                if not verified or verified[0] != 1:
                    return {'records': [], 'session': selected, 'cursor': after, 'blocked': True, 'has_more': False,
                            'available_sessions': sessions, 'reason': 'Unverified archive session was not read'}
                reset = bool(session and session != selected)
                if reset:
                    after = 0
                conditions, args = ['session=?', 'sequence>?'], [selected, after]
                if target:
                    conditions.append('instr(target,?)>0')
                    args.append(target)
                if kind:
                    conditions.append('kind=?')
                    args.append(kind)
                rows = connection.execute('SELECT sequence,payload FROM records WHERE ' + ' AND '.join(conditions) + ' ORDER BY sequence LIMIT ?', [*args, limit + 1]).fetchall()
                result, bytes_used = [], 0
                for sequence, payload in rows[:limit]:
                    size = len(payload.encode('utf-8'))
                    if bytes_used + size > 1048576:
                        break
                    result.append(json.loads(payload))
                    bytes_used += size
                last = connection.execute('SELECT MAX(sequence) FROM records WHERE session=?', (selected,)).fetchone()[0] or 0
                has_more = len(rows) > len(result)
                cursor = result[-1]['sequence'] if result else after
                if not has_more:
                    cursor = max(cursor, last)
                gaps = [{'first': row[0], 'last': row[1]} for row in connection.execute(
                    'SELECT first,last FROM gaps WHERE session=? ORDER BY first', (selected,))]
                return {'records': result, 'session': selected, 'cursor': cursor, 'reset': reset,
                        'gap': any(g['last'] > after for g in gaps), 'gaps': gaps, 'has_more': has_more,
                        'available_sessions': sessions, 'archived': True, 'archive_path': str(path)}
            finally:
                connection.close()
