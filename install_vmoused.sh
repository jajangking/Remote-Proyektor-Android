#!/system/bin/sh
# Tanam daemon virtual mouse (uhid) yang bertahan setelah ADB putus.
# Port default 9878.
PORT=${1:-9878}
PIDFILE=/data/local/tmp/vmoused.pid
LOG=/data/local/tmp/vmoused.log

if [ -f $PIDFILE ]; then kill "$(cat $PIDFILE)" 2>/dev/null; fi
for p in $(pgrep -f "vmoused32 $PORT" 2>/dev/null); do kill -9 "$p" 2>/dev/null; done
sleep 1

setsid sh -c "
  while true; do
    /data/local/tmp/vmoused32 $PORT >> $LOG 2>&1
    echo \"vmoused exit, restart \$(date)\" >> $LOG
    sleep 1
  done
" </dev/null >/dev/null 2>&1 &

sleep 2
echo "=== status port $PORT ==="
netstat -lnt 2>/dev/null | grep ":$PORT " || echo "PORT BELUM LISTEN"
echo "=== virtual mouse terdaftar? ==="
cat /proc/bus/input/devices 2>/dev/null | grep 'TV Remote' || echo "BELUM TERDAFTAR"
echo "=== log ==="
tail -3 $LOG 2>/dev/null
