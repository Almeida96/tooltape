"""Check the installed launchers, evidence files and offline command exit codes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--install-dir', type=Path, required=True)
installation = parser.parse_args().install_dir.resolve()
launcher = installation / 'bin' / ('tooltape.bat' if os.name == 'nt' else 'tooltape')


def run(*args, expected=0):
    result = subprocess.run([str(launcher), *map(str, args)], capture_output=True,
                            text=True, encoding='utf-8', timeout=45)
    assert result.returncode == expected, (args, result.returncode, result.stdout, result.stderr)
    return result.stdout


with tempfile.TemporaryDirectory(prefix='tooltape-cli-') as directory:
    root = Path(directory)
    demo = root / 'demo'
    assert run('--version').strip() == 'tooltape 0.1.0'
    result = json.loads(run('demo', '--dir', demo))
    assert result['offlineReplay'] == 'passed' and result['comparison'] == 'DRIFT'
    baseline, candidate = demo / 'baseline.tape.json', demo / 'candidate.tape.json'
    original = baseline.read_bytes()
    report = json.loads(run('compare', '--baseline', baseline, '--candidate', candidate, expected=2))
    assert report['comparison']['differenceCount'] > 0
    run('compare', '--baseline', baseline, '--candidate', baseline)
    summary = json.loads(run('inspect', '--tape', baseline))
    assert summary['baseline']['calls'] == 3
    assert 'arguments' not in summary['baseline']['sequence'][0]
    exchange = json.loads(run('show', '--tape', baseline, '--call', 'ticket-open'))
    assert exchange['arguments'] == {'id': 'INC-1042'}
    run('show', '--tape', baseline, '--call', 'absent', expected=1)
    run('inspect', '--tape', baseline, '--format', 'pdf', expected=1)
    run('inspect', '--tape', baseline, '--tape', baseline, expected=1)
    html = demo / 'comparison.html'
    html_before = hashlib.sha256(html.read_bytes()).hexdigest()
    run('inspect', '--tape', baseline, '--out', html, expected=1)
    assert hashlib.sha256(html.read_bytes()).hexdigest() == html_before
    run('demo', '--dir', demo, expected=1)
    assert baseline.read_bytes() == original

    altered = json.loads(original)
    altered['calls'][0]['arguments']['id'] = 'changed'
    tampered = root / 'tampered.json'
    tampered.write_text(json.dumps(altered), encoding='utf-8')
    run('inspect', '--tape', tampered, expected=1)
    run('serve', '--tape', tampered, expected=1)

    # Create-new validation happens before a scenario read or live executable launch.
    run('record', '--scenario', 'absent.json', '--out', baseline,
        '--', 'missing-tool-tape-test-executable', expected=1)
    assert baseline.read_bytes() == original
    assert '<script' not in html.read_text(encoding='utf-8').lower()
    assert 'Content-Security-Policy' in html.read_text(encoding='utf-8')

print('Installed CLI: real demo/replay, PASS/DRIFT/error exits, integrity, inspection and no-overwrite checks passed.')
