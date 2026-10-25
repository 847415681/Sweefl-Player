"""Check every decoded RGB recording frame for gross stale-color glass, not latch proof."""
import sys,json
from pathlib import Path
import numpy as np
p=Path(sys.argv[1]);results=[]
for amount in (0,50):
 a=np.memmap(p/f'all-{amount}.rgb',dtype=np.uint8,mode='r').reshape(-1,360,780,3)
 bad=[]; neutral=[]; eligible=0; backgroundColors=set()
 for i,im in enumerate(a):
  bg=np.median(im[95:110,210:250],axis=(0,1)).astype(float)
  if np.ptp(bg)>100: eligible+=1; backgroundColors.add(int(bg.argmax()))
  # Avoid glyphs, borders and seekbar: centers of three buttons and lower bar clear areas.
  rois=[im[168:173,302:307],im[153:158,380:386],im[168:173,463:468],im[287:295,200:230]]
  for j,r in enumerate(rois):
   c=np.median(r,axis=(0,1)).astype(float)
   if np.ptp(bg)>100 and np.ptp(c)<=40:neutral.append({'frame':i,'region':j,'glass':c.tolist()})
   if np.ptp(bg)>100 and np.ptp(c)>40 and c.argmax()!=bg.argmax():bad.append({'frame':i,'region':j,'video':bg.tolist(),'glass':c.tolist()})
 results.append({'blur':amount,'frames':len(a),'eligibleFrames':eligible,'backgroundColors':sorted(backgroundColors),'coverageValid':eligible>len(a)*.8 and len(backgroundColors)>=2,'neutralBackdropCandidates':neutral,'wrongDominantColor':bad})
(p/'all-frame-color-check.json').write_text(json.dumps(results,indent=2))
print(json.dumps(results,indent=2))
