#!/system/bin/sh
# Tanam remote daemon bersistat shell di proyektor.
# Bertahan setelah koneksi ADB putus. Keluaran: port yang dibuka.
PORT=${1:-9877}
PIDFILE=/data/local/tmp/remoted.pid
LOG=/data/local/tmp/remoted.log

# bereskan kalau sudah ada
if [ -f $PIDFILE ]; then kill "$(cat $PIDFILE)" 2>/dev/null; fi
for p in $(pgrep -f "nc -L -p $PORT" 2>/dev/null); do kill "$p" 2>/dev/null; done
sleep 1

# daemon utama: loop supaya kalau nc mati langsung hidup lagi
setsid sh -c "
  echo \"remote daemon start port=$PORT pid=\$\$\" > $LOG
  while true; do
    /system/bin/nc -L -p $PORT -s 0.0.0.0 /system/bin/sh >> $LOG 2>&1
    echo \"nc exit, restart \$(date)\" >> $LOG
    sleep 1
  done
" </dev/null >/dev/null 2>&1 &

echo $! > $PIDFILE
sleep 2

echo "=== status ==="
id -u
netstat -lnt 2>/dev/null | grep ":$PORT " || echo "PORT $PORT BELUM LISTEN"
echo "=== log ==="
cat $LOG 2>/dev/null | tail -5
