# TanitaGarminSync MVP 0.3

Android: MyTANITA / Tanita RD-953 → Garmin Connect.

## Co działa

- logowanie i pobranie CSV z `mytanita.eu`,
- parser 28 kolumn RD-953 + deduplikacja,
- pokazanie ostatniego pomiaru,
- logowanie Garmin przez stronę SSO (WebView) + DI OAuth2,
- tokeny Garmin i dane MyTANITA szyfrowane przez Android Keystore,
- generowanie FIT `weight_scale` i upload pełnego składu ciała do Garmin Connect,
- automatyczna synchronizacja w tle przez WorkManager,
- kontrola co 6 godzin, tylko gdy dostępna jest sieć,
- na zwykłym przebiegu wysyłany jest tylko najnowszy, jeszcze niewysłany pomiar,
- raz na 7 dni kontrola braków wyłącznie z ostatnich 7 dni,
- fingerprinty blokują ponowne wysłanie tych samych rekordów.

## Zachowanie przy aktualizacji z v0.2

Pierwsze włączenie automatycznej synchronizacji ustawia bieżący moment jako początek kontroli historii. Dzięki temu v0.3 **nie importuje automatycznie poprzedniego tygodnia**. Pierwszy przebieg sprawdza tylko najnowszy pomiar. Dopiero po 7 dniach wykonuje kontrolę brakujących rekordów z poprzednich 7 dni.

MyTANITA nie ma znanego publicznego endpointu `export-csv` z zakresem dat, więc serwer nadal zwraca CSV. Aplikacja ogranicza jednak logikę wysyłania do Garmin do najnowszego rekordu oraz 7-dniowego okna kontrolnego; nie wysyła całej historii.

## Kompilacja w VSCode / Windows

```powershell
.\build_debug.bat
```

Skrypt preferuje Javę dołączoną do Android Studio:

```text
C:\Program Files\Android Studio\jbr
```

APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Instalacja ADB:

```powershell
.\install_debug.bat
```

## Pierwsze uruchomienie v0.3

1. Wprowadź dane MyTANITA i pobierz pomiary.
2. Zostaw zaznaczone `Automatyczna synchronizacja`.
3. Zaloguj Garmin Connect.
4. Aplikacja zaplanuje pracę w tle; pierwszy przebieg nie cofnie się do starszych pomiarów.
5. Android WorkManager uruchamia zadania okresowo i nie gwarantuje dokładnej godziny — system może przesunąć wykonanie ze względu na oszczędzanie baterii.

## Ważne

Garmin Connect nie udostępnia zwykłego publicznego konsumenckiego API do tej synchronizacji. Logowanie korzysta z bieżącego przepływu SSO → DI OAuth2 i może wymagać zmian, jeśli Garmin zmieni mechanizm logowania.

Timestamp MyTANITA jest interpretowany jako `Europe/Warsaw`, a FIT zapisuje właściwy czas protokołu po konwersji.
