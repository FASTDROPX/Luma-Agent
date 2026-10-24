#!/usr/bin/env python3
"""Check bundled UI catalogue coverage and formatting placeholders; no network requests."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
catalogues = {code: json.loads((root / f'app/src/main/assets/i18n/{code}.json').read_text(encoding='utf-8'))
              for code in ('en', 'zh', 'de', 'fr')}
keys = set(catalogues['en'])
tokens = re.compile(r'\{\d+\}|%(?:\d+\$)?[\d.]*[sdf]')
for code, entries in catalogues.items():
    assert set(entries) == keys, f'{code}: different catalogue keys'
    for key, value in entries.items():
        assert sorted(tokens.findall(key)) == sorted(tokens.findall(value)), f'{code}: broken arguments: {key}'
for path in (root / 'app/src/main/java').rglob('*.kt'):
    for match in re.finditer(r'\btr\(\s*("(?:\\.|[^"\\])*")', path.read_text(encoding='utf-8')):
        key = json.loads(match[1].replace('\\$', '$'))
        if re.search('[А-Яа-яЁё]', key):
            assert key in keys, f'{path.name}: missing UI string: {key}'
print(f'OK: {len(keys)} strings in each of four UI languages; placeholders and literal coverage valid')
