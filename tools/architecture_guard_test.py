"""Exercise architecture rejection with a disposable source probe; never compile/package it."""
from pathlib import Path
import json,subprocess
root=Path(__file__).resolve().parents[1]
probe=root/'app/src/main/java/com/sweefl/player/core/designsystem/BoundaryProbe.kt'
expected='package com.sweefl.player.core.designsystem\n\nimport com.sweefl.player.data.history.PlaybackHistory\n'
assert probe.resolve()==root/'app/src/main/java/com/sweefl/player/core/designsystem/BoundaryProbe.kt'
assert not probe.exists(),'Do not replace an existing source file'
out=root/'docs/testing-refactor'; out.mkdir(parents=True,exist_ok=True)
command=['powershell','-NoProfile','-ExecutionPolicy','Bypass','-File',str(root/'build-local.ps1'),'-Tasks','checkArchitecture']
try:
    probe.write_text(expected,encoding='utf-8')
    negative=subprocess.run(command,cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace')
    log=negative.stdout+negative.stderr
    (out/'architecture-rejection.txt').write_text(log,encoding='utf-8')
    assert negative.returncode!=0 and 'forbidden dependency com.sweefl.player.data.history.PlaybackHistory' in log,log
finally:
    if probe.exists():
        assert probe.resolve()==root/'app/src/main/java/com/sweefl/player/core/designsystem/BoundaryProbe.kt'
        assert probe.read_text(encoding='utf-8')==expected,'Probe changed; refusing deletion'
        probe.unlink()
positive=subprocess.run(command,cwd=root,capture_output=True,text=True,encoding='utf-8',errors='replace')
(out/'architecture-pass.txt').write_text(positive.stdout+positive.stderr,encoding='utf-8')
assert positive.returncode==0,positive.stdout+positive.stderr
(out/'architecture-results.json').write_text(json.dumps({'invalidDependencyRejected':True,'cleanSourceAccepted':True,'temporaryProbeRemoved':not probe.exists()},indent=2),encoding='utf-8')
print('Architecture rejection and clean-source checks passed')
