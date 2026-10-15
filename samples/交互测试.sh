#!/system/bin/sh
# 导入 tubing 后执行；仅用于手动检查终端和后台行为。
printf '\033[36m交互终端测试\033[0m\n'
printf '请输入你的名字（提示没有换行）：'
IFS= read -r name
printf '\n你好，%s\n' "$name"
printf '请选择：1 查看终端尺寸，2 测试后台计数\n选择：'
IFS= read -r choice
case "$choice" in
  1) stty size ;;
  2) i=1; while [ "$i" -le 30 ]; do printf '后台计数 %s/30\n' "$i"; sleep 2; i=$((i+1)); done ;;
  *) printf '收到选择：%s\n' "$choice" ;;
esac
printf '按回车结束，预期退出码为 7：'
IFS= read -r done
exit 7
