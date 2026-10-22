#!/system/bin/sh
# map listening port -> proses/aplikasi (tanpa root)
TMP=/data/local/tmp/_portmap.txt
> $TMP
INODES=$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$4=="0A"{print $10, $2}')
echo "=== LISTEN (inode, local_addr) ==="
echo "$INODES"
echo ""
echo "=== MAP inode -> PID -> APP ==="
for pid in /proc/[0-9]*; do
  p=${pid#/proc/}
  [ -d "$pid/fd" ] || continue
  for fd in "$pid"/fd/*; do
    l=$(readlink "$fd" 2>/dev/null)
    case "$l" in
      socket:\[*\])
        ino=${l#socket:[}; ino=${ino%]}
        hit=$(echo "$INODES" | awk -v i="$ino" '$1==i{print $1" "$2}')
        if [ -n "$hit" ]; then
          cmd=$(tr '\0' ' ' < "$pid/cmdline" 2>/dev/null | cut -c1-90)
          echo "$hit pid=$p  $cmd" >> $TMP
        fi
        ;;
    esac
  done
done
sort -u $TMP
echo ""
echo "=== SEMUA PROSES (uid, nama) ==="
cat /proc/*/cmdline 2>/dev/null | tr '\0' '\n' >/dev/null
for pid in /proc/[0-9]*; do
  p=${pid#/proc/}
  c=$(tr '\0' ' ' < "$pid/cmdline" 2>/dev/null | cut -c1-70)
  [ -n "$c" ] && echo "$p $c"
done | head -60
