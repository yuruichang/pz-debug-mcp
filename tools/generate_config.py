import argparse
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--zomboid-dir', type=Path, default=Path.home() / 'Zomboid')
options = parser.parse_args()
runtime = root / '.venv' / ('Scripts/python.exe' if __import__('os').name == 'nt' else 'bin/python')
config = {'mcpServers': {'pz_debug': {
    'command': str(runtime),
    'args': ['-m', 'pz_debug_mcp.server', '--zomboid-dir', str(options.zomboid_dir.resolve())],
    'env': {'PYTHONUTF8': '1'},
}}}
path = root / 'mcp-config.json'
path.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(path)
