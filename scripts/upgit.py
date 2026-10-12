#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import argparse,os,shutil,subprocess,sys,time,base64,json,urllib.request
def run(cmd,**kw):
    p=subprocess.run(cmd,capture_output=True,text=True,**kw)
    if p.returncode!=0: sys.stderr.write(p.stdout+p.stderr); raise SystemExit(f"命令失败 rc={p.returncode}")
    return p.stdout
def main():
    ap=argparse.ArgumentParser(description="免 clone，直接强推 main 分支")
    ap.add_argument("token")
    ap.add_argument("apk")
    ap.add_argument("repo",nargs="?",default="xime-relay-test")
    ap.add_argument("--branch",default="main")
    args=ap.parse_args()
    token=args.token.strip()[6:].strip() if args.token.lower().startswith("token ") else args.token.strip()
    if "/" not in args.repo:
        req=urllib.request.Request("https://api.github.com/user",headers={"Authorization":"token "+token,"User-Agent":"upgit"})
        repo=f"{json.load(urllib.request.urlopen(req))['login']}/{args.repo}"
    else: repo=args.repo
    apk=os.path.abspath(args.apk)
    if not os.path.isfile(apk): raise SystemExit(f"APK 不存在: {apk}")
    size, branch=os.path.getsize(apk), args.branch
    fname=os.path.basename(apk)
    auth=base64.b64encode(f"x-access-token:{token}".encode()).decode()
    url=f"https://github.com/{repo}"
    workdir=os.path.join(os.path.dirname(apk),".upgit_work")
    if os.path.exists(workdir): shutil.rmtree(workdir)
    os.makedirs(workdir,exist_ok=True)
    def g(*gargs): return run(["git","-c",f"http.extraHeader=Authorization: Basic {auth}","-C",workdir,*gargs])
    print(f"[1/2] 初始化本地空壳仓库并提交...",flush=True)
    g("init","-q","-b",branch)
    in_repo=f"out/{fname}"
    dst=os.path.join(workdir,in_repo)
    os.makedirs(os.path.dirname(dst),exist_ok=True)
    shutil.copyfile(apk,dst)
    g("config","user.email","relay@local"); g("config","user.name","relay")
    g("add",in_repo)
    subprocess.run(["git","-C",workdir,"commit","-q","-m",f"relay {time.strftime('%Y%m%d%H%M%S')}"],capture_output=True)
    print(f"[2/2] 强推分支 {branch} 到远端...",flush=True)
    t0=time.time()
    subprocess.run(["git","-c",f"http.extraHeader=Authorization: Basic {auth}","-C",workdir,"push","--force",url,f"HEAD:refs/heads/{branch}","--progress"])
    dt=time.time()-t0
    shutil.rmtree(workdir,ignore_errors=True)
    raw=f"https://github.com/{repo}/raw/refs/heads/{branch}/{in_repo}"
    ghfast=f"https://ghfast.top/{raw}"
    print(f"PUSH_SECONDS={dt:.1f}")
    print(f"PUSH_SPEED_MBPS={size/dt/1048576:.2f}")
    print(f"rm {fname}; wget {ghfast}")
if __name__=="__main__": main()