"""Exercise both blur settings entry points and preserve comparison screenshots."""
import json,time
from device_lab import adb,OUT,launch,verify_device
from ui_lab import ui,tap,find,screenshot
from glass_gesture_regression import bounds,input_cmd,record,preferences
def main_page():
    input_cmd('motionevent CANCEL 0 0')
    adb('shell','am','force-stop','com.sweefl.player')
    adb('shell','am','start','-W','-f','0x10008000','-n','com.sweefl.player/.MainActivity')
    return ui('main')

# Run this suite only inside the guarded runner, which restores the user's preferences.
verify_device()
def set_blur(value,label):
    for attempt in range(14):
        tree=ui(label+'-'+str(attempt))
        choices=[n for n in tree.iter('node') if n.get('class')=='android.widget.SeekBar' and n.get('content-desc')=='玻璃模糊程度']
        if choices:
            node=choices[0];a,b,c,d=bounds(node)
            if d-b>=50:
                x=a+2 if value==0 else c-2 if value==100 else (a+c)//2
                input_cmd(f'tap {x} {(b+d)//2}')
                tree=ui(label+'-value')
                record(label+' shows requested percent',find(tree,str(value)+'%') is not None)
                actual=preferences().get('glass_blur_amount','0')
                record(label+' persists requested percent',actual==str(value),actual)
                return tree
        scroll=next((n for n in tree.iter('node') if n.get('scrollable')=='true'),None)
        assert scroll is not None,'Missing dialog scrolling area'
        a,b,c,d=bounds(scroll)
        input_cmd(f'swipe {(a+c)//2} {int(b+(d-b)*.80)} {(a+c)//2} {int(b+(d-b)*.30)} 600')
    raise AssertionError('Blur slider not fully reachable')

def player_blur(value,label):
    tree=ui(label+'-controls');tap(tree,'播放信息与解码');set_blur(value,label)
    input_cmd('keyevent 4');time.sleep(.8);screenshot(label+'-video')

main_page();tree=ui('library');tap(tree,'设置');set_blur(50,'library-50');screenshot('library-blur-50')
input_cmd('keyevent 4');main_page();tree=ui('restarted');tap(tree,'设置')
# Verify the initial restored value before modifying it again.
for attempt in range(12):
    tree=ui('restored-'+str(attempt))
    if find(tree,'50%') is not None:break
    scroll=next(n for n in tree.iter('node') if n.get('scrollable')=='true');a,b,c,d=bounds(scroll)
    input_cmd(f'swipe {(a+c)//2} {int(b+(d-b)*.8)} {(a+c)//2} {int(b+(d-b)*.3)} 600')
record('blur value survives process restart',find(tree,'50%') is not None)
input_cmd('keyevent 4')
launch('synthetic/glass-alignment-v032.mp4',extra=('--ez','keepControls','true'),key='blur-quality')
tree=ui('video');tap(tree,'暂停');tree=ui('paused')
a,b,c,d=bounds(next(tree.iter('node')))
if d>c:tap(tree,'切换横竖屏');tree=ui('landscape')
for value in [0,50,100,0]:player_blur(value,'blur-'+str(value))
# Fixed coordinates in the 2772x1280 landscape fixture: the transmitted 7,3 label,
# away from foreground controls and the refraction rim. Record detail, do not hide regressions.
from PIL import Image
import numpy as np
stats={}
for value in [0,50,100]:
    image=np.asarray(Image.open(OUT/'screenshots'/('blur-'+str(value)+'-video.png')).convert('RGB'),dtype=float)
    h,w=image.shape[:2];crop=image[int(974*h/1280):int(1027*h/1280),int(1015*w/2772):int(1120*w/2772)]
    gray=crop.mean(axis=2)
    stats[value]={'gradientEnergy':float(np.mean(np.diff(gray,axis=0)**2)+np.mean(np.diff(gray,axis=1)**2))}
(OUT/'blur-quality.json').write_text(json.dumps(stats,indent=2),encoding='utf-8')
print(stats,flush=True)
record('maximum blur softens transmitted detail',stats[100]['gradientEnergy']<stats[0]['gradientEnergy']*.6,stats)
