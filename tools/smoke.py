"""Real-device smoke test of browsing and reading under hard conditions.

    python tools/smoke.py [scenario ...]        no argument: all of them, in order
    python tools/smoke.py --list

Needs the debug build installed (it carries LeakCanary) and the PC reachable from the device.
Environment: DEVICE (default 192.168.0.123:5555), PC_HOST (default 192.168.0.200), SMOKE_OUT (folder for logs).

It clears the app's data before and after, changes system settings while it runs (rotation, font scale, display
density, "don't keep activities", status bar) and puts them back. It refuses the owner's phone.
A scenario fails on: a crash or ANR of the app in logcat, the app not being in front when it should be, or its
own check. At the end the app is sent to the background and LeakCanary's heap analysis is read from logcat.
"""
import os, re, subprocess, sys, tempfile, time, sqlite3, shutil

D = os.environ.get('DEVICE', '192.168.0.123:5555')
PC = os.environ.get('PC_HOST', '192.168.0.200')
PKG = 'com.rc.novelreader'
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get('SMOKE_OUT') or tempfile.mkdtemp(prefix='smoke-')
ENV = dict(os.environ, MSYS_NO_PATHCONV='1')
SITE, CHAOS, HIJACK = 8765, 8766, 8767
BOOK = 'http://%s:%d/book/88/' % (PC, SITE)

if D.startswith('192.168.0.208'):
    sys.exit("that is the owner's phone: it holds real reading data")


def adb(*a, raw=False, timeout=120):
    r = subprocess.run(['adb', '-s', D] + list(a), env=ENV, capture_output=True, timeout=timeout)
    return r.stdout if raw else r.stdout.decode('utf-8', 'replace')


def sh(cmd, timeout=120):
    return adb('shell', cmd, timeout=timeout)


def nodes():
    sh('uiautomator dump /sdcard/u.xml')
    out = []
    for m in re.finditer(r'<node ([^>]*?)/?>', sh('cat /sdcard/u.xml')):
        a = dict(re.findall(r'([\w\-]+)="([^"]*)"', m.group(1)))
        b = [int(x) for x in re.findall(r'\d+', a.get('bounds', ''))]
        if len(b) == 4 and b[2] > b[0] and b[3] > b[1]:
            out.append((a.get('resource-id', '').split('/')[-1], a.get('text', ''), (b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
    return out


def tap_id(rid, wait=15):
    end = time.time() + wait
    while True:
        for n in nodes():
            if n[0] == rid:
                sh('input tap %d %d' % (n[2], n[3]))
                return True
        if time.time() > end:
            # what was on the screen instead
            name = os.path.join(OUT, 'no-%s-%d' % (rid, time.time() % 100000))
            sh('screencap -p /sdcard/_smoke.png')
            adb('pull', '/sdcard/_smoke.png', name + '.png')
            open(name + '.txt', 'w', encoding='utf-8').write(sh('uiautomator dump /sdcard/u.xml') + '\n' + '\n'.join(map(str, nodes())))
            return False
        time.sleep(1)


def front():
    m = re.search(r'(?:topResumedActivity|mResumedActivity)[^\n]*?' + re.escape(PKG) + r'/([\w.]+)', sh('dumpsys activity activities'))
    return m.group(1).split('.')[-1] if m else ''


def wait_front(name, sec=15):
    end = time.time() + sec
    while time.time() < end:
        if front() == name:
            return True
        time.sleep(0.7)
    return False


def url(u):
    sh("am start -n %s/.browser.BrowserActivity --es q '%s'" % (PKG, u))


def launcher():
    sh('am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n %s/.shelf.ShelfActivity' % PKG)


def keys(code, n, pause=0.0):
    """n key presses in one shell (one adb round trip per press is too slow)."""
    sh('i=0; while [ $i -lt %d ]; do input keyevent %d; %s i=$((i+1)); done' % (n, code, 'sleep %.2f;' % pause if pause else ''), timeout=600)


def size():
    m = re.findall(r'(\d+)x(\d+)', sh('wm size'))[-1]
    return int(m[0]), int(m[1])


def meminfo():
    t = sh('dumpsys meminfo ' + PKG)
    def num(pat):
        m = re.search(pat, t)
        return int(m.group(1)) if m else -1
    return {'pss_kb': num(r'TOTAL PSS:\s+(\d+)'), 'java_kb': num(r'Java Heap:\s+(\d+)'), 'activities': num(r'Activities:\s+(\d+)'),
            'views': num(r'Views:\s+(\d+)'), 'webviews': num(r'WebViews:\s+(\d+)')}


def db():
    """A copy of the app's database, opened on the PC."""
    d = tempfile.mkdtemp(prefix='db-', dir=OUT)
    for f in ('novel.db', 'novel.db-wal', 'novel.db-shm'):
        data = adb('exec-out', 'run-as', PKG, 'cat', 'databases/' + f, raw=True)
        if data:
            open(os.path.join(d, f), 'wb').write(data)
    return sqlite3.connect(os.path.join(d, 'novel.db'))


def fresh():
    sh('am force-stop ' + PKG)
    sh('pm clear ' + PKG)
    # no update check (a request to GitHub), and leaving an unshelved book asks nothing
    prefs = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
             '    <boolean name="app_update" value="false" />\n    <string name="leave_unshelved">skip</string>\n</map>\n')
    subprocess.run(['adb', '-s', D, 'shell', 'run-as', PKG, 'sh', '-c',
                    '"mkdir -p shared_prefs && cat > shared_prefs/%s_preferences.xml"' % PKG], input=prefs.encode(), env=ENV, timeout=30)
    for p in ('POST_NOTIFICATIONS',):
        sh('pm grant %s android.permission.%s' % (PKG, p))


def open_reader(u=BOOK + '1001.html'):
    url(u)
    if not tap_id('fab', 25):
        return False
    return wait_front('ReaderActivity', 15)


def position():
    c = db()
    try:
        return c.execute('SELECT curIdx, curOffset, title FROM books ORDER BY lastReadAt DESC LIMIT 1').fetchone()
    except sqlite3.Error:
        row = c.execute('SELECT * FROM books ORDER BY 1 DESC LIMIT 1').fetchone()
        return row
    finally:
        c.close()


# ------------------------------------------------------------------ scenarios (return None, or a text = failure)

def s_browse_and_read():
    """the plain path: a chapter page is recognised, reading mode opens, pages turn, Back returns to the browser"""
    if not open_reader():
        return 'reading mode did not open from a chapter page (front: %s)' % front()
    keys(25, 30, 0.15)
    time.sleep(2)
    if front() != 'ReaderActivity':
        return 'the reader went away while turning pages (front: %s)' % front()
    sh('input keyevent 4')
    if not wait_front('BrowserActivity', 8):
        return 'Back from the reader did not return to the browser (front: %s)' % front()


def s_rotation_storm():
    """the screen turned twelve times while pages are being turned, then the same in the browser"""
    if not open_reader():
        return 'reading mode did not open'
    for i in range(12):
        sh('settings put system user_rotation %d' % (i % 4))
        keys(25, 2)
        time.sleep(0.6)
    sh('settings put system user_rotation 0')
    time.sleep(1.5)
    if front() != 'ReaderActivity':
        return 'the reader did not survive the rotations (front: %s)' % front()
    sh('input keyevent 4')
    wait_front('BrowserActivity', 8)
    for i in range(8):
        sh('settings put system user_rotation %d' % (i % 4))
        time.sleep(0.5)
    sh('settings put system user_rotation 0')
    time.sleep(1.5)
    if front() != 'BrowserActivity':
        return 'the browser did not survive the rotations (front: %s)' % front()


def s_huge_and_tiny():
    """font scale 2.0 and 0.85, display density doubled and halved: shelf, browser, reader and settings still open"""
    base = re.findall(r'(\d+)', sh('wm density'))
    base = int(base[0]) if base else 160
    for scale, dens in ((2.0, base), (0.85, base), (1.0, base * 2), (1.0, max(100, base // 2 + 20))):
        sh('settings put system font_scale %s' % scale)
        sh('wm density %d' % dens)
        time.sleep(2.5)
        launcher()
        if not wait_front('ShelfActivity', 10):
            return 'the shelf did not open at font scale %s, density %d (front: %s)' % (scale, dens, front())
        if not open_reader(BOOK + '1003.html'):
            return 'reading mode did not open at font scale %s, density %d' % (scale, dens)
        keys(25, 8, 0.1)
        adb('shell', 'screencap -p /sdcard/_smoke.png')
        adb('pull', '/sdcard/_smoke.png', os.path.join(OUT, 'scale%s_density%d.png' % (scale, dens)))
        sh('input keyevent 4')
        time.sleep(1)
    sh('settings put system font_scale 1.0')
    sh('wm density reset')
    time.sleep(2.5)
    if not sh('pidof ' + PKG).strip():
        return 'the app is gone after the display changes'


def s_dont_keep_activities():
    """"Don't keep activities" on: every screen left is destroyed and must come back as it was"""
    sh('settings put global always_finish_activities 1')
    try:
        if not open_reader(BOOK + '1004.html'):
            return 'reading mode did not open'
        keys(25, 9, 0.2)
        time.sleep(2.5)
        before = position()
        sh('input keyevent 3')
        time.sleep(2.5)
        launcher()
        time.sleep(3)
        if front() not in ('ReaderActivity', 'ShelfActivity', 'BrowserActivity'):
            return 'the app did not come back (front: %s)' % front()
        sh('input keyevent 3')
        time.sleep(1.5)
        after = position()
        if before and after and after[0] < before[0]:
            return 'the reading position went back from chapter %s to %s' % (before[0], after[0])
        url(BOOK)
        time.sleep(3)
        sh('input keyevent 3')
        time.sleep(1.5)
        url(BOOK + '1002.html')
        if not wait_front('BrowserActivity', 10):
            return 'the browser did not come back (front: %s)' % front()
    finally:
        sh('settings put global always_finish_activities 0')


def s_death_in_front():
    """the process dies with the reader in front (as when the system updates WebView): the next start is back in the book"""
    if not open_reader(BOOK + '1006.html'):
        return 'reading mode did not open'
    keys(25, 9, 0.2)
    time.sleep(3)
    before = position()
    sh('logcat -c')
    sh('am crash ' + PKG)
    time.sleep(4)
    sh('logcat -c')             # that crash was ours
    # the system may rebuild the screen underneath by itself; if it does not, the user starts the app
    if not wait_front('ReaderActivity', 4):
        launcher()
    if not wait_front('ReaderActivity', 15):
        return 'after dying in the reader the app started on %s' % (front() or 'nothing')
    time.sleep(3)
    sh('input keyevent 3')
    time.sleep(1.5)
    row = position()
    if not before or not row or row[0] != before[0]:
        return 'the book was at chapter index %s before the death and is at %s after it' % (before and before[0], row and row[0])


def s_background_kill():
    """killed in the background under memory pressure, with tabs open: the tabs wait, nothing is loaded for them"""
    for i in range(1, 6):
        url(BOOK + '%d.html' % (1010 + i))
        time.sleep(2.2)
    sh('input keyevent 3')
    time.sleep(2)
    sh('am send-trim-memory %s RUNNING_CRITICAL' % PKG)
    sh('am send-trim-memory %s COMPLETE' % PKG)
    time.sleep(1)
    sh('am kill ' + PKG)
    time.sleep(1.5)
    if sh('pidof ' + PKG).strip():
        sh('am force-stop ' + PKG)
    launcher()
    if not wait_front('ShelfActivity', 12) and front() == '':
        return 'the app did not start again'
    url(BOOK + '1016.html')
    if not wait_front('BrowserActivity', 12):
        return 'the browser did not open after the kill (front: %s)' % front()
    time.sleep(3)
    m = meminfo()
    if m['webviews'] > 7:
        return '%d WebViews alive after a restart: restored tabs were loaded' % m['webviews']


def s_tab_flood():
    """forty tabs opened one after another: at most six WebViews stay alive and memory stays bounded"""
    first = None
    for i in range(40):
        url('http://%s:%d/book/88/%d.html?flood=%d' % (PC, SITE, 1001 + i % 50, i))
        time.sleep(1.0)
        if i == 7:
            first = meminfo()
    time.sleep(3)
    m = meminfo()
    print('      memory after 8 tabs %s, after 40 tabs %s' % (first, m))
    if front() != 'BrowserActivity':
        return 'the browser is not in front (front: %s)' % front()
    if m['webviews'] > 7:
        return '%d WebViews alive with 40 tabs (the limit is 6)' % m['webviews']
    if first and first['pss_kb'] > 0 and m['pss_kb'] > first['pss_kb'] * 2.5 + 150000:
        return 'memory grew from %d to %d KB between 8 and 40 tabs' % (first['pss_kb'], m['pss_kb'])


def s_marathon():
    """four hundred page turns through the book, there and back: the reader stays, memory does not climb"""
    if not open_reader(BOOK + '1001.html'):
        return 'reading mode did not open'
    keys(25, 40)
    time.sleep(2)
    a = meminfo()
    keys(25, 200)
    keys(24, 80)
    keys(25, 80)
    time.sleep(3)
    b = meminfo()
    print('      memory after 40 turns %s, after 400 turns %s' % (a, b))
    if front() != 'ReaderActivity':
        return 'the reader went away (front: %s)' % front()
    row = position()
    if not row or row[0] < 5:
        return 'four hundred turns ended at chapter index %s' % (row[0] if row else None)
    if a['java_kb'] > 0 and b['java_kb'] > a['java_kb'] * 2 + 60000:
        return 'Java heap grew from %d to %d KB over the marathon' % (a['java_kb'], b['java_kb'])
    sh('input keyevent 4')


def s_bad_network():
    """a site that answers 503, cuts connections, stalls and dribbles: reading goes on or shows a failure, never dies"""
    u = 'http://%s:%d/book/99/1001.html' % (PC, CHAOS)
    ok = False
    for attempt in range(4):        # the first page itself may be one of the bad answers
        # a new address each time: one that is already open in a tab is only brought to the front
        url(u + '?try=%d' % attempt)
        if tap_id('fab', 25) and wait_front('ReaderActivity', 15):
            ok = True
            break
    if not ok:
        return 'reading mode never opened on the bad site (front: %s)' % front()
    for i in range(12):
        keys(25, 6, 0.3)
        time.sleep(4)
    if front() != 'ReaderActivity':
        return 'the reader went away on the bad site (front: %s)' % front()
    sh('input keyevent 4')


def s_site_goes_away(servers=None):
    """the site stops answering in the middle of a book and comes back: failure pages, then text again"""
    if not open_reader(BOOK + '1020.html'):
        return 'reading mode did not open'
    servers['stop_site']()
    try:
        keys(25, 25, 0.3)
        time.sleep(6)
        if front() != 'ReaderActivity':
            return 'the reader went away when the site stopped (front: %s)' % front()
        # rotated and left while a chapter is failing
        sh('settings put system user_rotation 1')
        time.sleep(1)
        sh('settings put system user_rotation 0')
        sh('input keyevent 3')
        time.sleep(1.5)
    finally:
        servers['start_site']()
    launcher()
    time.sleep(2)
    if front() not in ('ReaderActivity', 'ShelfActivity'):
        return 'the app did not come back (front: %s)' % front()
    keys(24, 3, 0.3)
    keys(25, 6, 0.5)
    time.sleep(3)
    if not sh('pidof ' + PKG).strip():
        return 'the app is gone'
    sh('input keyevent 4')


def s_hijacked_and_flaky():
    """a book whose site swaps ads for its next links, one that fails now and then, and one behind a human check"""
    for path in ('/h/c1.html', '/e/c1.html', '/v/c1.html'):
        if not open_reader('http://%s:%d%s' % (PC, HIJACK, path)):
            return 'reading mode did not open on %s (front: %s)' % (path, front())
        for i in range(5):
            keys(25, 6, 0.3)
            time.sleep(2.5)
        if front() != 'ReaderActivity':
            return 'the reader went away on %s (front: %s)' % (path, front())
        sh('input keyevent 4')
        time.sleep(1.5)


def s_churn():
    """screens opened and closed over and over (what a leak detector needs to see): reader, tabs, shelf"""
    for i in range(8):
        if not open_reader(BOOK + '%d.html' % (1030 + i)):
            return 'reading mode did not open in round %d (front: %s)' % (i, front())
        keys(25, 3, 0.1)
        sh('input keyevent 4')
        time.sleep(1.2)
    for i in range(6):
        launcher()
        time.sleep(1.0)
        sh('input keyevent 4')
        time.sleep(0.8)
    url(BOOK)
    if not wait_front('BrowserActivity', 10):
        return 'the browser did not come back (front: %s)' % front()


def monkey(events, seed):
    out = sh('monkey -p %s --pct-syskeys 0 --pct-appswitch 0 --ignore-security-exceptions --throttle 25 -s %d %d' % (PKG, seed, events), timeout=900)
    open(os.path.join(OUT, 'monkey-%d.txt' % seed), 'w', encoding='utf-8').write(out)
    if 'CRASH' in out or 'NOT RESPONDING' in out:
        m = re.search(r'// (CRASH|NOT RESPONDING)[^\n]*\n(?:[^\n]*\n){0,12}', out)
        return 'monkey (seed %d): %s' % (seed, m.group(0)[:1200] if m else 'crash')


def s_monkey_browser():
    """six thousand random touches and keys in the browser on the mock site"""
    url(BOOK)
    time.sleep(3)
    return monkey(6000, 11)


def s_monkey_reader():
    """six thousand random touches and keys starting in the reader (menus, settings sheets, selection, whatever it hits)"""
    if not open_reader(BOOK + '1008.html'):
        return 'reading mode did not open'
    return monkey(6000, 23)


SCENARIOS = [s_browse_and_read, s_rotation_storm, s_huge_and_tiny, s_dont_keep_activities, s_death_in_front,
             s_background_kill, s_tab_flood, s_marathon, s_bad_network, s_site_goes_away, s_hijacked_and_flaky,
             s_churn, s_monkey_browser, s_monkey_reader]


# ------------------------------------------------------------------ the run

def problems():
    """Crashes and ANRs of the app in logcat since the last clear."""
    log = adb('logcat', '-d', '-v', 'brief')
    found = []
    for m in re.finditer(r'FATAL EXCEPTION[^\n]*\n(?:[^\n]*AndroidRuntime[^\n]*\n){1,40}', log):
        if PKG in m.group(0):
            lines = [l.split('): ', 1)[-1] for l in m.group(0).splitlines()]
            keep = [l for l in lines if 'Exception' in l or 'Error' in l or 'Caused by' in l or 'com.rc.' in l]
            found.append('CRASH ' + ' | '.join(keep[:8]))
    for m in re.finditer(r'ANR in ' + re.escape(PKG) + r'[^\n]*(?:\n[^\n]*Reason:[^\n]*)?', log):
        found.append(m.group(0).split('): ', 1)[-1][:300])
    return found, log


def leaks():
    """Sends the app to the background and reads LeakCanary's verdict. @return (text, failed)"""
    sh('logcat -c')
    launcher()
    time.sleep(2)
    for i in range(3):
        sh('input keyevent 4')
        time.sleep(1)
    sh('input keyevent 3')
    end = time.time() + 240
    log, seen = '', False
    while time.time() < end:
        time.sleep(10)
        log = adb('logcat', '-d', '-s', 'LeakCanary:*')
        seen = seen or 'retained' in log.lower() or 'dumping' in log.lower()
        if 'HEAP ANALYSIS RESULT' in log:
            time.sleep(5)
            log = adb('logcat', '-d', '-s', 'LeakCanary:*')
            break
        if not seen and time.time() > end - 190:
            break
    open(os.path.join(OUT, 'leakcanary.txt'), 'w', encoding='utf-8').write(log)
    if 'HEAP ANALYSIS RESULT' not in log:
        if seen:
            return 'LeakCanary saw retained objects but its analysis did not finish in four minutes (see leakcanary.txt)', True
        return 'LeakCanary: no retained objects, no heap dump needed', False
    m = re.search(r'(\d+) APPLICATION LEAKS', log)
    n = int(m.group(1)) if m else 0
    if n == 0:
        return 'LeakCanary: heap analysed, 0 application leaks', False
    body = '\n'.join(l.split('LeakCanary: ', 1)[-1] for l in log.splitlines())
    start = body.find('APPLICATION LEAKS')
    stop = body.find('LIBRARY LEAKS')
    return 'LeakCanary: %d APPLICATION LEAK(S)\n%s' % (n, body[start:stop if stop > start else start + 6000][:6000]), True


def main():
    args = sys.argv[1:]
    if '--list' in args:
        for s in SCENARIOS:
            print('%-24s %s' % (s.__name__[2:], s.__doc__))
        return 0
    todo = [s for s in SCENARIOS if not args or s.__name__[2:] in args]
    if 'device' not in adb('get-state'):
        sys.exit('device %s is not attached (adb connect %s)' % (D, D))
    if not adb('shell', 'pm path ' + PKG).strip():
        sys.exit('the app is not installed on ' + D)
    print('device %s, logs in %s' % (D, OUT))

    site = os.path.join(OUT, 'site')
    subprocess.run([sys.executable, os.path.join(HERE, 'mock_site', 'gen.py'), site], check=True, env=ENV)
    procs = {}

    def start(name, cmd, log):
        procs[name] = subprocess.Popen(cmd, stdout=open(os.path.join(OUT, log), 'a'), stderr=subprocess.STDOUT)

    def listening(port):
        import socket
        try:
            socket.create_connection(('127.0.0.1', port), timeout=1).close()
            return True
        except OSError:
            return False

    def up(port):
        end = time.time() + 30
        while not listening(port):
            if time.time() > end:
                sys.exit('the server on port %d did not start' % port)
            time.sleep(0.3)

    # Windows lets a second server bind a port that is taken, and requests then go to either one
    for port in (SITE, CHAOS, HIJACK):
        if listening(port):
            sys.exit('port %d is already in use: stop the server that is running there' % port)

    def start_site():
        start('site', [sys.executable, '-m', 'http.server', str(SITE), '--bind', '0.0.0.0', '--directory', site], 'site.log')
        up(SITE)

    def stop_site():
        procs['site'].kill()
        procs['site'].wait()

    start_site()
    start('chaos', [sys.executable, os.path.join(HERE, 'mock_site', 'chaos_server.py'), site, str(CHAOS)], 'chaos.log')
    start('hijack', [sys.executable, os.path.join(HERE, 'mock_site', 'hijack_server.py')], 'hijack.log')
    up(CHAOS)
    up(HIJACK)
    servers = {'start_site': start_site, 'stop_site': stop_site}

    saved = {k: sh('settings get system ' + k).strip() for k in ('accelerometer_rotation', 'user_rotation', 'font_scale')}
    sh('settings put system accelerometer_rotation 0')
    sh('settings put system user_rotation 0')
    sh('cmd statusbar disable-for-setup true')      # a random swipe must not reach the quick settings (Wi-Fi carries adb)
    sh('input keyevent KEYCODE_WAKEUP')
    failed = []
    try:
        fresh()
        for s in todo:
            name = s.__name__[2:]
            print('%-24s %s' % (name, s.__doc__), flush=True)
            sh('logcat -c')
            t = time.time()
            try:
                why = s(servers) if s is s_site_goes_away else s()
            except subprocess.TimeoutExpired as e:
                why = 'a device command did not return: %s' % ' '.join(e.cmd)[:200]
            found, log = problems()
            if why:
                found.insert(0, why)
            if found:
                open(os.path.join(OUT, name + '.logcat.txt'), 'w', encoding='utf-8').write(log)
                failed.append(name)
            print('  %s  %d s%s' % ('FAIL' if found else 'ok', time.time() - t, ''.join('\n      ' + f.replace('\n', '\n      ') for f in found)), flush=True)
            if adb('get-state').strip() != 'device':
                print('the device went away; stopping')
                break
        text, bad = leaks()
        print(text)
        if bad:
            failed.append('leaks')
        print('memory at the end: %s' % meminfo())
    finally:
        for k, v in saved.items():
            if v and v != 'null':
                sh('settings put system %s %s' % (k, v))
        sh('settings put global always_finish_activities 0')
        sh('wm density reset')
        sh('cmd statusbar disable-for-setup false')
        sh('am force-stop ' + PKG)
        sh('pm clear ' + PKG)
        sh('rm -rf /sdcard/u.xml /sdcard/_smoke.png /sdcard/Download/leakcanary-%s' % PKG)      # LeakCanary's heap dumps
        for p in procs.values():
            p.kill()
        shutil.rmtree(site, ignore_errors=True)
    print('\n%s: %d of %d scenarios failed%s' % ('FAILED' if failed else 'PASSED', len([f for f in failed if f != 'leaks']), len(todo),
                                                 ' (' + ', '.join(failed) + ')' if failed else ''))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
