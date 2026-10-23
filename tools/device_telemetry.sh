#!/system/bin/sh
# Read-only, bounded telemetry for the test app and Android codec services.
printf 'uptime '; cat /proc/uptime
printf 'clockTicks '; getconf CLK_TCK
printf 'cpu '; head -n 1 /proc/stat
for pname in com.sweefl.player media.swcodec media.codec; do
  for pid in $(pidof "$pname"); do
    printf 'process %s %s ' "$pname" "$pid"
    cat "/proc/$pid/stat" 2>/dev/null
  done
done
for node in current_now voltage_now charge_counter temp status capacity; do
  printf 'battery %s ' "$node"
  cat "/sys/class/power_supply/battery/$node" 2>/dev/null
done
for node in /sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq; do
  printf 'frequency %s ' "$node"; cat "$node" 2>/dev/null
done
