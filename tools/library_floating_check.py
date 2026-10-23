"""Read-only library media visual evidence in short landscape viewport; no media imports."""
import time
from device_lab import adb,OUT,verify_device
from ui_lab import screenshot,ui
verify_device()
adb('shell','am','force-stop','com.sweefl.player')
adb('shell','wm','user-rotation','lock','1')
adb('shell','am','start','-W','-n','com.sweefl.player/.MainActivity')
time.sleep(2);ui('landscape-library');screenshot('landscape-before')
adb('shell','input','swipe','400','850','400','400','700')
time.sleep(.3);screenshot('landscape-scrolled');ui('after-scroll')
adb('shell','dumpsys','gfxinfo','com.sweefl.player','reset');time.sleep(3)
(OUT/'library-idle-gfxinfo.txt').write_text(adb('shell','dumpsys','gfxinfo','com.sweefl.player'),encoding='utf-8')
print('Library floating scroll and idle evidence captured; inspect images',flush=True)
