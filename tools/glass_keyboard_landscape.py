import re,shlex,json
from device_lab import adb,verify_device,OUT
from ui_lab import ui,tap,tap_node,find,screenshot
from glass_gesture_regression import input_cmd,record,bounds
verify_device(); root=lambda c:adb('shell','su -c '+shlex.quote(c))
rotation=root('wm user-rotation').strip(); assert re.fullmatch(r'free|lock [0-3]',rotation),rotation
try:
    root('wm user-rotation lock 1'); input_cmd('motionevent CANCEL 0 0')
    adb('shell','am','force-stop','com.sweefl.player')
    adb('shell','am','start','-W','-f','0x10008000','-n','com.sweefl.player/.MainActivity')
    tree=ui('landscape-library'); x1,y1,x2,y2=bounds(next(tree.iter('node'))); assert x2-x1>y2-y1
    tap(tree,'打开网络视频'); tree=ui('landscape-network')
    tap_node(next(n for n in tree.iter('node') if n.get('class')=='android.widget.EditText'))
    input_cmd('text https://example.com/video.mp4'); tree=ui('landscape-network-ime'); screenshot('landscape-network-ime')
    record('landscape keyboard preserves editable URL and focus',any(n.get('class')=='android.widget.EditText' and 'https://example.com/video.mp4' in n.get('text','') and n.get('focused')=='true' for n in tree.iter('node')))
    node=find(tree,'播放'); record('landscape keyboard leaves action button visible',node is not None and bounds(node)[3]-bounds(node)[1]>30)
finally:
    input_cmd('motionevent CANCEL 0 0'); adb('shell','am','force-stop','com.sweefl.player'); root('wm user-rotation '+rotation)
    assert root('wm user-rotation').strip()==rotation
    (OUT/'rotation-restoration.json').write_text(json.dumps({'restored':True,'original':rotation}))
