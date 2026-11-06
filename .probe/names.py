
import re,sys
src=open(r"/e/agentHome/bt-share-app/tools/build-libtorrent-android/build/src/libtorrent4j/swig/deps/libtorrent/src/alert.cpp".replace('/e/','E:/').replace('/','\\'),encoding='utf-8',errors='replace').read()
start=src.index('static std::array<char const*, num_alert_types> const names = {{')
end=src.index('}};',start)
block=src[start:end]
lines=block.splitlines()[1:]
# evaluate #if TORRENT_ABI_VERSION == 1  -> we want the else branch (ABI 2)
out=[];stack=[]
for ln in lines:
    s=ln.strip()
    if s.startswith('#if'):
        cond = '1' if 'TORRENT_ABI_VERSION == 1' in s else None
        stack.append(cond)
        continue
    if s.startswith('#else'):
        stack[-1] = 'not1'
        continue
    if s.startswith('#endif'):
        stack.pop(); continue
    m=re.search(r'"([a-z0-9_]*)"',s)
    if not m: continue
    skip = any(c=='1' for c in stack)          # inside ABI==1 branch -> skip
    if skip: continue
    out.append(m.group(1))
print("count",len(out))
for i,n in enumerate(out):
    if i>=95: print(i,n)
