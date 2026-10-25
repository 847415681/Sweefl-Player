"""Check entry/resize colors using actual control bounds, including portrait letterbox."""
import json,re,sys
from pathlib import Path
import xml.etree.ElementTree as ET
import numpy as np
from PIL import Image
p=Path(sys.argv[1]);w,h=Image.open(p/'screenshots/after-resize.png').size
source=p/'paused.xml'
if not source.exists():source=p/'entered.xml'
tree=ET.parse(source).getroot();parents={child:parent for parent in tree.iter() for child in parent}
def control(label):
 n=next(n for n in tree.iter('node') if n.get('content-desc')==label)
 while n.get('clickable')!='true' and n in parents:n=parents[n]
 assert n.get('clickable')=='true',label
 return tuple(map(int,re.findall(r'\d+',n.get('bounds',''))))
boxes=[control('后退 10 秒'),control('播放'),control('快进 10 秒')]
def patch(im,box,x1,x2,y1,y2):
 a,b,c,d=box
 left,right=[round((a+(c-a)*x)*780/w) for x in (x1,x2)]
 top,bottom=[round((b+(d-b)*y)*360/h) for y in (y1,y2)]
 assert right>left and bottom>top
 return im[top:bottom,left:right]
a=np.memmap(p/'frames.rgb',dtype=np.uint8,mode='r').reshape(-1,360,780,3)
visible=0;bad=[];neutral=[];first=None
for i,im in enumerate(a):
 bg=np.median(im[int(360*.43):int(360*.46),int(780*.40):int(780*.43)],axis=(0,1))
 glyph=patch(im,boxes[1],.35,.65,.35,.65)
 if np.ptp(bg)<100 or (glyph.min(axis=2)>180).sum()<5:continue
 visible+=1
 if first is None:first=i
 for j,box in enumerate(boxes):
  value=np.median(patch(im,box,.35,.65,.22,.34),axis=(0,1))
  if np.ptp(value)<=40:neutral.append([i,j])
  elif value.argmax()!=bg.argmax():bad.append([i,j])
report=dict(nativeSize=[w,h],controlBounds=boxes,frames=len(a),visibleColoredFrames=visible,
 firstVisible=first,coverageValid=visible>50,wrongColorCandidates=bad,neutralCandidates=neutral,
 note='Central controls only: portrait FIT legitimately places top/bottom glass over black letterbox.')
(p/'frame-check.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
assert report['coverageValid'] and not bad and not neutral
