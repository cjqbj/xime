#!/system/bin/sh
# Android 图案锁解锁脚本 - L 型手势 (2589)
# 在设备上执行: sh unlock_pattern.sh
# 需要 root 或 adb shell 权限

# 触摸屏事件节点（用 getevent -pl 确认）
DEV=/dev/input/event0

# 九宫格坐标 (1440x2560 屏幕，通过 screencap + 像素扫描确认)
# 列: 342(左) 720(中) 1098(右)
# 行: 1302(上) 1678(中) 2056(下)
# L = 2→5→8→9 = (720,1302)→(720,1678)→(720,2056)→(1098,2056)

se() { sendevent "$DEV" "$1" "$2" "$3"; }
sleep_() { sleep 0.04; }

# === 按下 (touch down) ===
se 3 57 1        # TRACKING_ID = 1
se 3 53 720      # X = 720 (2)
se 3 54 1302     # Y = 1302 (2)
se 3 48 50       # TOUCH_MAJOR = 50（必须，否则系统不识别为有效按压）
se 1 330 1       # BTN_TOUCH down
se 0 0 0         # SYN_REPORT
sleep_

# === 移动到 5 (720,1678) ===
for y in 1340 1385 1430 1475 1520 1565 1610 1655 1678; do
  se 3 53 720; se 3 54 "$y"; se 0 0 0; sleep_
done

# === 移动到 8 (720,2056) ===
for y in 1720 1765 1810 1855 1900 1945 1990 2035 2056; do
  se 3 53 720; se 3 54 "$y"; se 0 0 0; sleep_
done

# === 移动到 9 (1098,2056) ===
for x in 765 810 855 900 945 990 1035 1080 1098; do
  se 3 53 "$x"; se 3 54 2056; se 0 0 0; sleep_
done

# === 抬起 (touch up) ===
se 3 57 -1       # TRACKING_ID = -1 (结束)
se 1 330 0       # BTN_TOUCH up
se 0 0 0         # SYN_REPORT
