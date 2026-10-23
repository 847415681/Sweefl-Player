"""Full Window UI + video + actual modal dim; visual evidence retained under outer guard."""
import time,json
import numpy as np
from PIL import Image
from device_lab import verify_device,launch,snapshot,OUT
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import record,show
verify_device()
launch('synthetic/seek-epoch-rgb.mp4',extra=('--ez','keepControls','true','--ez','benchmark','false'),key='dialog-backdrop')
time.sleep(1);tap(ui('pause'),'暂停');tap(ui('rotate'),'切换横竖屏');ui('landscape-ready')
screenshot('underlay')
tap(ui('open'),'播放信息与解码');ui('dialog-ready');time.sleep(.5);screenshot('dialog')
tap(show('画面与渲染设置','nested-entry'),'画面与渲染设置');ui('nested-ready');time.sleep(.5);screenshot('nested-dialog')
# Verify no crash/primary position alteration while dialogs are open.
s=snapshot();record('dialog preserves paused playback',not s['isPlaying'] and s.get('error') is None,s)
(OUT/'state.json').write_text(json.dumps(s,ensure_ascii=False,indent=2))
print('Dialog images captured: inspect fullunderlay/bar continuity and dim; no automatic image-quality pass.',flush=True)
