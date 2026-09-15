@echo off
rem =====================================================================
rem  Auto Checkin - one-click token extractor (Windows)
rem
rem  Usage:  double-click this file, or run  run.cmd  in cmd.exe
rem
rem  What it does:
rem    1. finds Python (py launcher first, then python on PATH)
rem    2. installs pycryptodome if it is missing
rem    3. runs extract_tokens.py  ->  checkin_auth.json
rem    4. copies checkin_auth.json to the clipboard, so you can paste
rem       it straight into the App (long-press the input box -> Paste)
rem
rem  NOTE: keep this file pure ASCII and CRLF.
rem  cmd.exe parses .cmd files with the OEM code page, so UTF-8 text
rem  here would corrupt the parsing of every following line.
rem =====================================================================
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"
set "PYTHONUTF8=1"

echo ==========================================
echo   Auto Checkin  -  Token Extractor
echo ==========================================
echo.

rem ---------- 1. locate python ----------
set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY (
    where python >nul 2>nul && set "PY=python"
)
if not defined PY (
    echo [ERROR] Python not found.
    echo         Install Python 3.8+ and tick "Add python.exe to PATH".
    echo         https://www.python.org/downloads/
    goto :end
)
echo [1/3] Python found:
%PY% --version
echo.

rem ---------- 2. make sure pycryptodome is available ----------
%PY% -c "import Crypto" >nul 2>nul
if errorlevel 1 (
    echo [2/3] pycryptodome missing, installing ...
    %PY% -m pip install -q --disable-pip-version-check pycryptodome
    if errorlevel 1 (
        echo [ERROR] pip install failed. Run it manually:
        echo         %PY% -m pip install pycryptodome
        goto :end
    )
) else (
    echo [2/3] pycryptodome already installed.
)
echo.

rem ---------- 3. extract ----------
rem Move the previous result aside first, so a failed run can never
rem make us copy a stale file into the clipboard.
if exist "checkin_auth.json" move /y "checkin_auth.json" "checkin_auth.json.bak" >nul

echo [3/3] Reading desktop client credentials ...
echo.
%PY% extract_tokens.py
echo.

if not exist "checkin_auth.json" (
    echo [ERROR] checkin_auth.json was not generated.
    echo         Make sure Trae / WorkBuddy desktop is logged in with the
    echo         account you want, then run this script again.
    echo         Previous result kept at checkin_auth.json.bak
    goto :end
)

for %%A in ("checkin_auth.json") do set "SZ=%%~zA"
if "%SZ%"=="0" (
    echo [ERROR] checkin_auth.json is empty.
    goto :end
)

type "checkin_auth.json" | clip
if errorlevel 1 (
    echo [WARN] Could not copy to clipboard.
    echo        Open checkin_auth.json and copy its content manually.
) else (
    echo ==========================================
    echo   DONE - content copied to clipboard
    echo ------------------------------------------
    echo   Next: open the App, tap [add account],
    echo   long-press the input box and Paste.
    echo ==========================================
)

:end
echo.
pause
endlocal
