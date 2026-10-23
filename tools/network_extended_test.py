from codec_matrix import adb, snapshot, OUT
import argparse,json,time

parser=argparse.ArgumentParser()
parser.add_argument('--only',default='')
parser.add_argument('--output',default='network-extended.json')
parser.add_argument('--fallback-https',action='store_true')
options=parser.parse_args()

cases=[
 ('extensionless HLS','http://127.0.0.1:8765/require-ua/synthetic/hls/live','application/vnd.apple.mpegurl'),
 ('extensionless DASH','http://127.0.0.1:8765/require-ua/synthetic/dash/live','application/dash+xml'),
 ('HTTP redirect keeps UA','http://127.0.0.1:8765/redirect/synthetic/avc1080.mp4',None),
 ('HTTPS HEVC public fixture','https://raw.githubusercontent.com/androidx/media/8c6678b657ede1e7883fc164ef73ed483c7796c3/libraries/test_data/src/test/assets/media/mp4/h265_bframes.mp4',None),
]
if options.fallback_https:
    cases=[('HTTPS public alternate fixture','https://media.w3.org/2010/05/sintel/trailer.mp4',None)]
if options.only:
    cases=[case for case in cases if options.only.lower() in case[0].lower()]
results=[]
for name,url,mime in cases:
    adb('shell','am','force-stop','com.sweefl.player')
    extra=['-t',mime] if mime else []
    adb('shell','am','start','-W','-n','com.sweefl.player/.PlayerActivity','-d',url,*extra,'--es','userAgent','Sweefl-QA/0.2','--ez','diagnostics','true','--ez','benchmark','true')
    sample={}
    for _ in range(10):
        time.sleep(2); sample=snapshot()
        if sample.get('error') or sample.get('renderedFrames',0)>10: break
    row={'case':name,'url':url,'mime':mime,'snapshot':sample,'passed':sample.get('error') is None and sample.get('renderedFrames',0)>0}
    results.append(row); print(name,row['passed'],sample.get('error'),flush=True)
    (OUT/options.output).write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    if mime:
        # Same URL without type/UA must recover persisted stream configuration.
        adb('shell','am','force-stop','com.sweefl.player')
        adb('shell','am','start','-W','-n','com.sweefl.player/.PlayerActivity','-d',url,'--ez','diagnostics','true','--ez','benchmark','true')
        time.sleep(5); sample=snapshot()
        results.append({'case':name+' persisted type and UA','snapshot':sample,'passed':sample.get('error') is None and sample.get('renderedFrames',0)>0})
        (OUT/options.output).write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
adb('shell','am','force-stop','com.sweefl.player')
