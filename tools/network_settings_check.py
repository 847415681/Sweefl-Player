"""Persistent network settings through real UI; outer guard restores original preferences."""
import time,json
from device_lab import adb,OUT,verify_device,launch,snapshot
from ui_lab import ui,tap,tap_node,find,screenshot
verify_device()
def open_settings():
 adb('shell','am','force-stop','com.sweefl.player');adb('shell','am','start','-W','-n','com.sweefl.player/.MainActivity')
 tap(ui('library'),'设置');tap(ui('settings'),'网络播放与缓存');return ui('network')
def fields(tree):return [n for n in tree.iter('node') if n.get('class')=='android.widget.EditText']
def fill(node,value):
 tap_node(node);adb('shell','input','keyevent','123')
 for i in range(8):adb('shell','input','keyevent','67')
 adb('shell','input','text',value)
t=open_settings();f=fields(t);assert len(f)==2
fill(f[0],'33');t=ui('invalid');save=find(t,'保存');assert any(n.get('enabled')=='false' and save in list(n.iter('node')) for n in t.iter('node'))
fill(fields(t)[0],'32');t=ui('valid-threads');fill(fields(t)[1],'5')
adb('shell','input','keyevent','4');t=ui('save');assert find(t,'保存').get('enabled')=='true';tap(t,'保存');tap(ui('done'),'完成')
t=open_settings();values=[n.get('text') for n in fields(t)];assert values==['32','5'],values
screenshot('persisted');tap(t,'取消');tap(ui('done-again'),'完成')
launch('synthetic/avc1080.mp4',key='buffer-five');time.sleep(3);s=snapshot();assert s.get('error') is None and s['isPlaying'],s
assert s['bufferedDurationMs']<15000,s
(OUT/'results.json').write_text(json.dumps({'invalid33Rejected':True,'persistedAfterRestart':values,'fiveSecondTargetPlayback':s},ensure_ascii=False,indent=2))
print('Network settings invalid input, persistence and five-second target passed',flush=True)
