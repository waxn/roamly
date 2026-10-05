#pragma once
#include <Arduino.h>
#include <time.h>

// Civil date -> unix seconds (Howard Hinnant's days_from_civil).
inline long long civilToUnix(int y, int m, int d, int hh, int mm, int ss) {
  y -= m <= 2;
  const int era = (y >= 0 ? y : y - 399) / 400;
  const unsigned yoe = (unsigned)(y - era * 400);
  const unsigned doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  const unsigned doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  long days = (long)era * 146097 + (long)doe - 719468;
  return (long long)days * 86400 + hh * 3600 + mm * 60 + ss;
}

// Current local offset from UTC in seconds, under whatever TZ is active.
inline long localOffsetS(time_t now) {
  struct tm lt;
  localtime_r(&now, &lt);
  return (long)(civilToUnix(lt.tm_year + 1900, lt.tm_mon + 1, lt.tm_mday, lt.tm_hour, lt.tm_min, lt.tm_sec) - now);
}

// "12s" / "4m" / "2h" / "3d"
inline String agoShort(uint32_t secs) {
  if (secs < 60) return String(secs) + "s";
  if (secs < 3600) return String(secs / 60) + "m";
  if (secs < 172800) return String(secs / 3600) + "h";
  return String(secs / 86400) + "d";
}

// "31h", "2h05", "45m"
inline String hoursFmt(float h) {
  if (isnan(h) || h < 0) return "--";
  if (h > 999) return ">999h";
  if (h >= 10) return String((int)lroundf(h)) + "h";
  int mins = (int)lroundf(h * 60);
  if (mins < 60) return String(mins) + "m";
  char b[12];
  snprintf(b, sizeof b, "%dh%02d", mins / 60, mins % 60);
  return String(b);
}
