#!/system/bin/sh
if [ -t 0 ]; then printf '标准输入：终端（PTY）\n'; else printf '标准输入：管道\n'; fi
if [ -t 1 ]; then printf '标准输出：终端（PTY）\n'; else printf '标准输出：管道\n'; fi
printf '请输入中文或数字，然后按回车（可先退格修改）：'
IFS= read -r answer
printf '\n已收到：%s\n' "$answer"
printf '选择 1 开始后台计数，其他内容直接结束：'
IFS= read -r choice
if [ "$choice" = 1 ]; then
  i=1
  while [ "$i" -le 30 ]; do printf '后台计数 %s/30\n' "$i"; sleep 2; i=$((i+1)); done
fi
printf '测试结束，预期退出码为 7。\n'
exit 7
