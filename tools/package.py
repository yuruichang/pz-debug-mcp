"""Create separate distributable mod and source archives."""

import hashlib
import tomllib
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
dist.mkdir(exist_ok=True)
mod = root / 'Contents/mods/PZDebugMCP'
version = tomllib.loads((root / 'pyproject.toml').read_text(encoding='utf-8'))['project']['version']
if not (mod / '42/media/java/PZDebugMCP.jar').is_file():
    raise SystemExit('Build the Java bridge before packaging')
with ZipFile(dist / f'PZDebugMCP-mod-{version}.zip', 'w', ZIP_DEFLATED) as archive:
    for file in sorted(mod.rglob('*')):
        if file.is_file():
            archive.write(file, 'PZDebugMCP/' + file.relative_to(mod).as_posix())
    for name in ('README.md', 'README.en.md'):
        archive.write(root / name, name)
with ZipFile(dist / f'PZDebugMCP-workshop-{version}.zip', 'w', ZIP_DEFLATED) as archive:
    for name in ('workshop.txt', 'preview.png', 'README.md', 'README.en.md'):
        archive.write(root / name, 'PZDebugMCP/' + name)
    for file in sorted(mod.rglob('*')):
        if file.is_file():
            archive.write(file, 'PZDebugMCP/Contents/mods/PZDebugMCP/' + file.relative_to(mod).as_posix())
with ZipFile(dist / f'PZDebugMCP-source-{version}.zip', 'w', ZIP_DEFLATED) as archive:
    for file in sorted(root.rglob('*')):
        relative = file.relative_to(root)
        if not file.is_file() or any(part in {'.venv', 'build', 'dist', '__pycache__', '.git', '.baoyu-skills', 'recordings', 'Lua', 'Saves'} or part.endswith('.egg-info') for part in relative.parts):
            continue
        if file.name.startswith('mcp-config.') or file.name == 'dependencies-lock.txt' or file.suffix in {'.sqlite3', '.db', '.log'} or '.sqlite3-' in file.name:
            continue
        archive.write(file, 'PZDebugMCP/' + relative.as_posix())
manifest = []
for name in (f'PZDebugMCP-mod-{version}.zip', f'PZDebugMCP-workshop-{version}.zip', f'PZDebugMCP-source-{version}.zip'):
    path = dist / name
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest.append(f'{digest}  {name}')
    print(f'{name}: {path.stat().st_size} bytes')
(dist / 'SHA256SUMS.txt').write_text('\n'.join(manifest) + '\n', encoding='utf-8')
