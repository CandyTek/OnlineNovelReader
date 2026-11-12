"""usage: python tools/uidrive.py step [step...]; step = tap:<text> | long:<text> | text:<ascii> | key:<code> | wait:<sec> | dump[:filter] | shot:<name> (writes <name>.png, half size) | url:<address> | xy:<x,y> | longxy:<x,y>"""
import subprocess, sys, os, re, time
D = os.environ.get('UIDRIVE_DEVICE', '192.168.0.123:5555')  # the owner's phone: 192.168.0.208:5555
env = dict(os.environ, MSYS_NO_PATHCONV='1')
def adb(*a):
    return subprocess.run(['adb', '-s', D, 'shell'] + list(a), env=env, capture_output=True).stdout.decode('utf-8', 'replace')
def nodes():
    adb('uiautomator', 'dump', '/sdcard/u.xml')
    x = adb('cat', '/sdcard/u.xml')
    res = []
    for m in re.finditer(r'<node ([^>]*?)/?>', x):
        a = dict(re.findall(r'([\w\-]+)="([^"]*)"', m.group(1)))
        b = re.findall(r'\d+', a.get('bounds', ''))
        if len(b) != 4: continue
        res.append((a.get('text', ''), a.get('content-desc', ''), a.get('resource-id', '').split('/')[-1], (int(b[0]) + int(b[2])) // 2, (int(b[1]) + int(b[3])) // 2, a.get('checked', '')))
    return res
def find(sub):
    for n in nodes():
        if sub == n[2] or sub in n[0] or sub in n[1]:
            return n
    print('NOT FOUND:', sub); return None
for step in sys.argv[1:]:
    k, _, v = step.partition(':')
    if k == 'tap':
        n = find(v)
        if n: adb('input', 'tap', str(n[3]), str(n[4]))
        time.sleep(1.2)
    elif k == 'long':
        n = find(v)
        if n: adb('input', 'swipe', str(n[3]), str(n[4]), str(n[3]), str(n[4]), '900')
        time.sleep(1.2)
    elif k == 'xy':
        x, y = v.split(','); adb('input', 'tap', x, y); time.sleep(1.2)
    elif k == 'longxy':
        x, y = v.split(','); adb('input', 'swipe', x, y, x, y, '900'); time.sleep(1.2)
    elif k == 'text':
        adb('input', 'text', v); time.sleep(0.8)
    elif k == 'key':
        adb('input', 'keyevent', v); time.sleep(1.0)
    elif k == 'wait':
        time.sleep(float(v))
    elif k == 'url':
        adb('am', 'start', '-n', 'com.rc.novelreader/.browser.BrowserActivity', '--es', 'q', v); time.sleep(3.5)
    elif k == 'dump':
        for n in nodes():
            if (n[0] or n[1] or n[2]) and (not v or v in n[0] or v in n[2] or v in n[1]):
                sys.stdout.buffer.write(('%s | %s | %s | %d,%d %s\n' % (n[0][:60], n[1][:20], n[2], n[3], n[4], n[5] if n[5] == 'true' else '')).encode('utf-8'))
    elif k == 'shot':
        adb('screencap', '-p', '/sdcard/_s.png')
        subprocess.run(['adb', '-s', D, 'pull', '/sdcard/_s.png', v + '.png'], env=env, capture_output=True)
        try:
            from PIL import Image
            im = Image.open(v + '.png'); im = im.resize((im.width // 2, im.height // 2)); im.save(v + '.png')
        except Exception as e: print('no resize', e)
