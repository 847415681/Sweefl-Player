"""Remove unrelated system-picker/launcher text from our generated UI evidence."""
from pathlib import Path
import os
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]/os.environ.get('SWEEFL_TEST_OUT','docs/testing-v020')
allowed={'浏览','Download','确定','取消','关闭','完成','安全访问','应用仅可以访问您选择的文件，无法访问其他内容，保护您的隐私安全'}
changed=0
for path in root.rglob('*.xml'):
    tree=ET.parse(path); dirty=False
    for node in tree.iter('node'):
        if node.get('package')=='com.sweefl.player': continue
        for key in ('text','content-desc'):
            value=node.get(key,'')
            if value and value not in allowed and not value.startswith(('Sweefl-test.','Sweefl-UI-Test-')):
                node.set(key,'[unrelated UI text redacted]'); dirty=True
    if dirty:
        tree.getroot().set('unrelatedTextRedacted','true'); tree.write(path,encoding='utf-8',xml_declaration=True); changed+=1
print('Sanitized generated UI evidence files:',changed)
