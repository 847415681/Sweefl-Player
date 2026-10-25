"""Small UI automation helpers; touch only named Sweefl controls/test files."""
from codec_matrix import adb, OUT
import re, uuid, xml.etree.ElementTree as ET


def ui(name):
    path=OUT/(name+'.xml')
    for attempt in range(3):
        remote='/data/local/tmp/sweefl-ui-'+uuid.uuid4().hex+'.xml'
        try:
            try:
                message=adb('shell','uiautomator','dump',remote)
            except RuntimeError as error:
                with (OUT/'ui-dump-retries.log').open('a',encoding='utf-8') as log:
                    log.write(f'{name}: attempt {attempt+1}: dump process failed: {error!r}\n')
                if attempt == 2:raise
                continue
            if 'dumped to:' not in message:
                print('UI dump not confirmed, retry',name,attempt+1,repr(message),flush=True)
                with (OUT/'ui-dump-retries.log').open('a',encoding='utf-8') as log:
                    log.write(f'{name}: attempt {attempt+1}: {message!r}\n')
                continue
            adb('pull',remote,str(path))
            return ET.parse(path).getroot()
        finally:
            assert re.fullmatch(r'/data/local/tmp/sweefl-ui-[0-9a-f]{32}\.xml',remote)
            adb('shell','rm','-f',remote)
    raise RuntimeError('No fresh UI hierarchy for '+name)

def find(tree,label):
    return next((n for n in tree.iter('node') if label in (n.get('text'),n.get('content-desc'))),None)

def tap_node(node):
    if node is None: raise AssertionError('Missing UI node')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','su','-c',f'input tap {(x1+x2)//2} {(y1+y2)//2}')

def tap(tree,label):
    node=find(tree,label)
    if node is None: raise AssertionError('Missing control '+label)
    tap_node(node)

def find_test_document(tree,name,prefix):
    if find(tree,name) is not None: return tree
    assert re.fullmatch(r'Sweefl-test\.(srt|vtt|ass)',name),name
    tap(tree,'搜索'); tree=ui(prefix+'-search')
    fields=[node for node in tree.iter('node') if node.get('class') in ('android.widget.EditText','android.widget.AutoCompleteTextView')]
    assert len(fields)==1,'Expected one file-search field'
    tap_node(fields[0])
    adb('shell','su','-c',f'input text {name}')
    adb('shell','su','-c','input keyevent 66')
    for attempt in range(3):
        tree=ui(prefix+'-search-results-'+str(attempt))
        if find(tree,name) is not None: return tree
    raise AssertionError('Test document not found: '+name)

def screenshot(name):
    adb('shell','screencap','-p','/data/local/tmp/sweefl-v020.png')
    path=OUT/'screenshots'/f'{name}.png'; path.parent.mkdir(exist_ok=True)
    adb('pull','/data/local/tmp/sweefl-v020.png',str(path))
    return path

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(); parser.add_argument('command'); parser.add_argument('value',nargs='?',default='current'); args=parser.parse_args()
    if args.command=='dump':
        tree=ui(args.value)
        for node in tree.iter('node'):
            if node.get('text') or node.get('content-desc') or node.get('class')=='android.widget.EditText':
                print(node.attrib)
    elif args.command=='tap': tap(ui('before-tap'),args.value)
    elif args.command=='screen': print(screenshot(args.value))
