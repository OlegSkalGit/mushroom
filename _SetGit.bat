@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: Перевірка чи є віддалений репозиторій
git remote get-url origin >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [Помилка] Remote 'origin' не знайдено або це не репозиторій.
    pause
    exit /b 1
)

:: Зберігаємо URL віддаленого репозиторію
for /f "delims=" %%i in ('git remote get-url origin') do set "REMOTE_URL=%%i"

echo [*] Скидання репозиторію в чистий стан...
rmdir /s /q .git

echo [*] Створення нового чистого репозиторію...
git init -b main
git remote add origin %REMOTE_URL%

echo [*] Додавання поточних файлів...
git add .

echo [*] Створення єдиного початкового коміту...
git commit -m "Initial commit (full project reset)"

echo [*] Примусовий запис на сервер (перезапис усього на origin/main)...
git push --force origin main

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [✓] Успішно! Віддалений репозиторій повністю перезаписано з нуля.
) else (
    echo.
    echo [!] Виникла помилка під час відправки.
)

pause
