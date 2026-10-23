"""Paired, bounded on-device performance sampling. Never treats charging current as app watts."""
from codec_matrix import adb, launch, snapshot, OUT
import json, re, time, statistics


def telemetry():
    text=adb('shell','su','-c','sh /data/local/tmp/sweefl-telemetry.sh')
    item={'processes':{},'battery':{},'frequency':{}}
    for line in text.splitlines():
        if line.startswith('uptime '): item['uptime']=float(line.split()[1])
        elif line.startswith('clockTicks '): item['hz']=int(line.split()[1])
        elif line.startswith('process '):
            name,pid,raw=line[len('process '):].split(' ',2)
            fields=raw.rsplit(') ',1)[1].split()
            item['processes'][name+':'+pid]=int(fields[11])+int(fields[12])
        elif line.startswith('battery '):
            fields=line.split(maxsplit=2)
            if len(fields)==3:
                try: item['battery'][fields[1]]=int(fields[2])
                except ValueError: item['battery'][fields[1]]=fields[2]
        elif line.startswith('frequency '):
            fields=line.split()
            if len(fields)==3: item['frequency'][fields[1]]=int(fields[2])
    return item

configs=[
 ('avc-auto-hidden','synthetic/avc1080.mp4','AUTO',False,False),
 ('hevc-auto-hidden','synthetic/hevc1080.mp4','AUTO',False,False),
 ('hevc-software-hidden','synthetic/hevc1080.mp4','SOFTWARE',False,False),
 ('avc-controls-no-refraction','synthetic/avc1080.mp4','AUTO',False,True),
 ('avc-controls-refraction','synthetic/avc1080.mp4','AUTO',True,True),
]
results=[]
for repeat in range(2):
    # Reverse second run to limit monotonic heat/order bias.
    for name,path,mode,glass,controls in (configs if repeat==0 else list(reversed(configs))):
        launch(path,mode,extra=('--ez','landscape','true','--ez','glass',str(glass).lower(),'--ez','keepControls',str(controls).lower()),key=f'perf-{name}-{repeat}')
        time.sleep(6)
        first=snapshot(); start=telemetry(); samples=[start]
        for _ in range(8):
            time.sleep(3)
            sample=telemetry(); samples.append(sample)
            if sample['battery'].get('temp',0)>430: raise RuntimeError('Stopped measurement: battery temperature above 43 C')
        last=snapshot()
        intervals=[]
        for a,b in zip(samples,samples[1:]):
            elapsed=b['uptime']-a['uptime']; hz=b.get('hz',100)
            cpu={}
            for key,val in b['processes'].items():
                if key in a['processes']: cpu[key.split(':')[0]]=cpu.get(key.split(':')[0],0)+100*(val-a['processes'][key])/hz/elapsed
            intervals.append(cpu)
        def mean(key): return round(statistics.mean(x.get(key,0) for x in intervals),2)
        mem=adb('shell','dumpsys','meminfo','com.sweefl.player')
        (OUT/f'memory-{name}-{repeat}.txt').write_text(mem,encoding='utf-8')
        match=re.search(r'TOTAL PSS:\s*([\d,]+)',mem)
        rendered=last.get('renderedFrames',0)-first.get('renderedFrames',0)
        dropped=last.get('droppedFrames',0)-first.get('droppedFrames',0)
        row={'case':name,'repeat':repeat+1,'decoder':last.get('videoDecoder'),'error':last.get('error'),
             'elapsedSeconds':round(samples[-1]['uptime']-start['uptime'],2),'renderedFramesDelta':rendered,'droppedFramesDelta':dropped,
             'appCpuPercentOneCore':mean('com.sweefl.player'),'softwareCodecServiceCpuPercentOneCore':mean('media.swcodec'),
             'codecServiceCpuPercentOneCore':mean('media.codec'),'totalPssKb':int(match[1].replace(',','')) if match else None,
             'batteryTemperatureCStart':start['battery'].get('temp',0)/10,'batteryTemperatureCEnd':samples[-1]['battery'].get('temp',0)/10,
             'batteryNetCurrentUaMean':round(statistics.mean(x['battery'].get('current_now',0) for x in samples)),
             'powerCaveat':'USB charging: net battery current is not app or decoder power; no per-rail power measurement',
             'firstSnapshot':first,'lastSnapshot':last,'samples':samples,'cpuIntervals':intervals}
        results.append(row)
        (OUT/'performance.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
        print(name,repeat+1,'CPU',row['appCpuPercentOneCore'],row['softwareCodecServiceCpuPercentOneCore'],'drop',dropped,'PSS',row['totalPssKb'],flush=True)
adb('shell','am','force-stop','com.sweefl.player')
