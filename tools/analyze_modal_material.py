"""Find first visible modal frame and flag neutral material drops in colored fixture.
Designed for landscape avc1080 test and the modal_material_check recording.
Candidates require visual review; this does not prove exact refraction geometry.
"""
import json,sys
from pathlib import Path
import numpy as np
from PIL import Image
p=Path(sys.argv[1]);out=[]
width,height=Image.open(p/'screenshots'/'info-open.png').size
landscape=width>height
for name in ('info-open','blur-drag','gestures-open'):
 a=np.memmap(p/(name+'.rgb'),dtype=np.uint8,mode='r').reshape(-1,360,780,3)
 visible=[];neutral=[]
 for i,im in enumerate(a):
  title=im[59:80,148:290]
  if ((title.min(axis=2)>150)&(title.max(axis=2)-title.min(axis=2)<60)).sum()<90:continue
  visible.append(i)
  colors=[np.median(im[98:110,x:x+10],axis=(0,1)) for x in (220,330,430,540)]
  if sum(float(np.ptp(c))<45 for c in colors)>=3:neutral.append(i)
 out.append({'name':name,'frames':len(a),'visibleFrames':len(visible),'coverageValid':landscape and len(visible)>20,'firstVisible':visible[0] if visible else None,'neutralCandidates':neutral})
(p/'material-check.json').write_text(json.dumps(out,indent=2))
print(json.dumps([{**x,'neutralCandidates':len(x['neutralCandidates'])} for x in out],indent=2))
