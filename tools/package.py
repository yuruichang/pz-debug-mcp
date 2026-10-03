"""Create separate distributable mod and source archives."""

import hashlib
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
dist.mkdir(exist_ok=True)
mod = root / 'Contents/mods/PZDebugMCP'
with ZipFile(dist / 'PZDebugMCP-mod-0.1.0.zip', 'w', ZIP_DEFLATED) as archive:
    for file in sorted(mod.rglob('*')):
        if file.is_file():
            archive.write(file, 'PZDebugMCP/' + file.relative_to(mod).as_posix())
    archive.write(root / 'README.md', 'PZDebugMCP-README.md')
with ZipFile(dist / 'PZDebugMCP-source-0.1.0.zip', 'w', ZIP_DEFLATED) as archive:
    for file in sorted(root.rglob('*')):
        relative = file.relative_to(root)
        if not file.is_file() or any(part in {'.venv', 'build', 'dist', '__pycache__', '.git'} or part.endswith('.egg-info') for part in relative.parts):
            continue
        if file.name.startswith('mcp-config.') or file.name == 'dependencies-lock.txt':
            continue
        archive.write(file, 'PZDebugMCP/' + relative.as_posix())
manifest = []
for name in ('PZDebugMCP-mod-0.1.0.zip', 'PZDebugMCP-source-0.1.0.zip'):
    path = dist / name
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest.append(f'{digest}  {name}')
    print(f'{name}: {path.stat().st_size} bytes')
(dist / 'SHA256SUMS.txt').write_text('\n'.join(manifest) + '\n', encoding='utf-8')
