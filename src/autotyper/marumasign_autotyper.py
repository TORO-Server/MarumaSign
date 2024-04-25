import pygetwindow as gw
import pyautogui as ag
import sys

if __name__ == "__main__":
    try:
        minecraftWindow: gw.Win32Window = gw.getWindowsWithTitle('Minecraft* 1.20.4')[0]

        # 引数取得
        s: str = sys.argv[1]
        # 背面書き込み不可
        if len(s) > 4:
            sys.exit(1)
        # 
        for i in range(0, len(s), 15):
            # Minecraftウィンドウに強制フォーカス
            minecraftWindow.activate()
            # 入力・改行
            ag.write(s[i:i+15])
            ag.press("enter")
    except Exception as e:
        sys.exit(-1)

    sys.exit(0)
