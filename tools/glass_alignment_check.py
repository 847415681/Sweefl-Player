"""Measure glass alignment without asserting old/new match-rate differences.

Parent owns installation, state backup/restoration, server and adb reverse 8765.
Run exclusively on the device; no settings/prefs APIs are used. Each capture uses
benchmark playback and verifies a paused frame across controls-on/off screenshots.
Example: python tools/glass_alignment_check.py --out docs/testing-v032/alignment-old --label old
Requires Pillow/numpy. No device activity occurs until main() runs.
"""
import argparse
import json
import os
import time
import traceback
import numpy as np
from PIL import Image

FIXTURE = 'synthetic/glass-alignment-v032.mp4'
MODES = ('portrait-fit', 'portrait-zoom', 'landscape-fit', 'landscape-zoom')
PALETTE = np.array([(240,24,24),(24,240,24),(24,24,240),(240,240,24),(240,24,240),(24,240,240)], dtype=np.float32)
PROTOTYPES = (PALETTE - 24) / 216
NAMES = ('red','green','blue','yellow','magenta','cyan')
LABELS = {'back':'后退 10 秒', 'play':'播放', 'forward':'快进 10 秒'}


def classify(rgb):
    """Remove brightness before palette classification; reject white/weak signal."""
    v = np.asarray(rgb, dtype=np.float32)
    low, high = v.min(axis=-1), v.max(axis=-1)
    chroma = high - low
    norm = (v - low[...,None]) / np.maximum(chroma[...,None], 1)
    distance = ((norm[...,None,:] - PROTOTYPES) ** 2).sum(axis=-1)
    valid = (high >= 40) & (chroma >= 18) & (chroma / np.maximum(high,1) >= .38)
    valid &= distance.min(axis=-1) <= .25
    return np.where(valid, distance.argmin(axis=-1), -1)


def score_button(visible, reference, button):
    h, w = reference.shape[:2]
    a,b,c,d = button['bounds']
    cx,cy,r = (a+c)/2, (b+d)/2, min(c-a,d-b)/2
    if r < 8:
        raise ValueError('Button too small for sampling: '+str(button))
    step, margin = max(2,int(r/24)), max(3,int(round(r*.14)))
    xx,yy = np.meshgrid(np.arange(max(0,int(cx-.68*r)),min(w,int(cx+.68*r)+1),step),
                       np.arange(max(0,int(cy-.68*r)),min(h,int(cy+.68*r)+1),step))
    rr = (xx-cx)**2 + (yy-cy)**2
    ring = (rr >= (.22*r)**2) & (rr <= (.68*r)**2)
    x,y = xx[ring],yy[ring]
    candidates = len(x)
    inside = (x>=margin)&(y>=margin)&(x<w-margin)&(y<h-margin)
    x,y = x[inside],y[inside]
    expected = classify(reference[y,x])
    stable = expected >= 0
    # Reject every reference point within the small-refraction margin of a
    # palette boundary or white marker. No global offset/search is fitted.
    for dy in range(-margin,margin+1):
        for dx in range(-margin,margin+1):
            stable &= classify(reference[y+dy,x+dx]) == expected
    x,y,expected = x[stable],y[stable],expected[stable]
    eligible = len(x)
    observed = classify(visible[y,x])
    usable = observed >= 0
    x,y,expected,observed = x[usable],y[usable],expected[usable],observed[usable]
    matched = expected == observed
    count = len(x)
    rate = float(matched.mean()) if count else None
    coverage = count/eligible if eligible else 0.0
    assessment = ('insufficient-samples' if count<24 or coverage<.35 else
                  'possible-large-misalignment' if rate<.80 else 'predominantly-aligned')
    confusion = np.zeros((6,6), dtype=int)
    np.add.at(confusion,(expected,observed),1)
    return {**button,
            'sampling':{'inner_radius_fraction':.22,'outer_radius_fraction':.68,
                        'grid_step_px':step,'edge_exclusion_and_refraction_tolerance_px':margin},
            'candidate_points':candidates,'reference_eligible_points':eligible,
            'excluded_control_low_chroma_or_nonpalette':eligible-count,
            'sample_points':count,'matched_points':int(matched.sum()),
            'match_rate':rate,'reference_coverage':coverage,'assessment':assessment,
            'confusion_rows_reference_columns_observed':confusion.tolist(),
            'point_columns':['screen_x','screen_y','reference_color_id','observed_color_id'],
            'points':np.column_stack((x,y,expected,observed)).tolist()}


class Capture:
    def __init__(self):
        # Helpers bind OUT at import, so main configures the environment first.
        import device_lab
        import ui_lab
        import glass_gesture_regression
        self.device,self.ui,self.g = device_lab,ui_lab,glass_gesture_regression

    def tree_until(self, tag, predicate, attempts=4):
        # ui() retries at most three times; every adb operation has a timeout.
        for index in range(attempts):
            tree = self.ui.ui(f'{tag}-{index}')
            if predicate(tree): return tree
            time.sleep(.25)
        raise RuntimeError('UI state not reached within bounded attempts: '+tag)

    def rendered_frame(self):
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            state=self.device.snapshot()
            if state.get('renderedFrames',0)>0: return state
            time.sleep(.25)
        raise RuntimeError('No rendered video frame within bounded wait')

    def paused_snapshot(self):
        deadline,previous = time.monotonic()+15,None
        while time.monotonic()<deadline:
            state = self.device.snapshot()  # transport timeout: 30 seconds
            key = (state.get('positionMs'),state.get('renderedFrames'))
            if (not state.get('isPlaying',True) and state.get('renderedFrames',0)>0
                    and key[0] is not None and key==previous): return state
            previous = key
            time.sleep(.25)
        raise RuntimeError('Playback did not settle to a paused rendered frame')

    def landscape(self, tree):
        a,b,c,d = self.g.bounds(next(tree.iter('node')))
        return c-a>d-b

    def button(self, tree, label):
        node = self.ui.find(tree,label)
        if node is None: raise RuntimeError('Missing central control: '+label)
        parents = {child:parent for parent in tree.iter() for child in parent}
        while node is not None:
            if node.get('clickable')=='true':
                rect = self.g.bounds(node)
                if len(rect)==4 and rect[2]>rect[0] and rect[3]>rect[1]:
                    return {'label':label,'bounds':rect,
                            'center':[(rect[0]+rect[2])/2,(rect[1]+rect[3])/2],
                            'bounds_source':'clickable-semantic-node-or-ancestor'}
            node = parents.get(node)
        raise RuntimeError('Cannot determine full clickable bounds for '+label)

    def blank_point(self, tree, w, h):
        blockers=[]
        for node in tree.iter('node'):
            if node.get('clickable')=='true':
                rect=self.g.bounds(node)
                if len(rect)==4 and (rect[2]-rect[0])*(rect[3]-rect[1])<w*h*.4:
                    blockers.append(rect)
        for fx,fy in ((.15,.35),(.85,.35),(.12,.65),(.88,.65),(.5,.30)):
            x,y=int(fx*w),int(fy*h)
            if not any(a-12<=x<=c+12 and b-12<=y<=d+12 for a,b,c,d in blockers): return x,y
        raise RuntimeError('No safe blank-area tap coordinate')

    def mode(self, name):
        landscape,zoom=name.startswith('landscape'),name.endswith('zoom')
        self.g.cancel()
        self.device.launch(FIXTURE,extra=('--ez','keepControls','true'),key='alignment-'+name)
        tree=self.tree_until(name+'-launched',lambda t:self.ui.find(t,'切换横竖屏') is not None)
        if self.landscape(tree)!=landscape: self.ui.tap(tree,'切换横竖屏')
        tree=self.tree_until(name+'-orientation',lambda t:self.landscape(t)==landscape)
        desired='恢复完整画面' if zoom else '填满画面，裁切边缘'
        other='填满画面，裁切边缘' if zoom else '恢复完整画面'
        if self.ui.find(tree,desired) is None: self.ui.tap(tree,other)
        tree=self.tree_until(name+'-fit-state',lambda t:self.ui.find(t,desired) is not None)
        self.rendered_frame()
        tree=self.tree_until(name+'-frame-ready',lambda t:any(self.ui.find(t,label) is not None for label in ('播放','暂停')))
        if self.ui.find(tree,'暂停') is not None: self.ui.tap(tree,'暂停')
        tree=self.tree_until(name+'-paused',lambda t:self.ui.find(t,'播放') is not None)
        self.paused_snapshot()
        time.sleep(1.1)  # bounded refresh of the 500ms paused decorative sample
        buttons={key:self.button(tree,label) for key,label in LABELS.items()}
        before=self.paused_snapshot()
        visible_path=self.ui.screenshot(name+'-controls')
        visible=np.array(Image.open(visible_path).convert('RGB'))
        h,w=visible.shape[:2]
        if (w>h)!=landscape: raise RuntimeError('Screenshot orientation mismatch')
        x,y=self.blank_point(tree,w,h)
        self.g.input_cmd(f'tap {x} {y}')
        self.tree_until(name+'-hidden',lambda t:all(self.ui.find(t,label) is None
                        for label in (*LABELS.values(),'暂停','切换横竖屏')))
        hidden=self.paused_snapshot()
        reference_path=self.ui.screenshot(name+'-reference')
        after=self.paused_snapshot()
        reference=np.array(Image.open(reference_path).convert('RGB'))
        if reference.shape!=visible.shape: raise RuntimeError('Reference dimensions changed')
        keys=('positionMs','renderedFrames')
        diagnostics=[{k:s.get(k) for k in (*keys,'isPlaying')} for s in (before,hidden,after)]
        if not all(before[k]==hidden[k]==after[k] for k in keys):
            raise RuntimeError('Paused frame changed across screenshots: '+repr(diagnostics))
        return {'mode':name,'status':'captured','same_frame':True,'screen_size':[w,h],
                'confirmed_fit_action_label':desired,'blank_tap':[x,y],'diagnostics':diagnostics,
                'controls_screenshot':str(visible_path.relative_to(self.device.OUT)),
                'reference_screenshot':str(reference_path.relative_to(self.device.OUT)),
                'buttons':{key:score_button(visible,reference,value) for key,value in buttons.items()}}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',default=os.environ.get('SWEEFL_TEST_OUT','docs/testing-v032/glass-alignment'))
    parser.add_argument('--label',default='current',help='Report label only, e.g. old/new')
    args=parser.parse_args()
    os.environ['SWEEFL_TEST_OUT']=args.out
    capture=Capture()
    out=capture.device.OUT/'glass-alignment-results.json'
    if out.exists(): raise FileExistsError('Preserve earlier evidence; choose a new --out: '+str(out))
    report={'schemaVersion':1,'label':args.label,'fixture':FIXTURE,
            'fixture_grid':{'size':[1920,1080],'columns':6,'rows':8,'cell_size':[320,135],
                            'palette_rgb':PALETTE.astype(int).tolist(),'color_rule':'(column + 2 * row) % 6'},
            'color_names':list(NAMES),
            'score_policy':'Report only; never assert match-rate threshold. Insufficient samples explicit.',
            'modes':[]}
    def save(): out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    capture.device.verify_device()
    save()
    try:
        for mode in MODES:
            try: result=capture.mode(mode)
            except Exception as exc:
                result={'mode':mode,'status':'capture-error','error':str(exc),'traceback':traceback.format_exc()}
            report['modes'].append(result)
            save()
            if result['status']=='captured':
                for name,button in result['buttons'].items():
                    print(mode,name,'samples=',button['sample_points'],'match_rate=',button['match_rate'],
                          'coverage=',button['reference_coverage'],button['assessment'],flush=True)
            else: print(mode,result['error'],flush=True)
    finally:
        capture.g.cancel()
        save()
    print('Report:',out,flush=True)
    return int(any(mode['status']!='captured' for mode in report['modes']))


if __name__=='__main__':
    raise SystemExit(main())
