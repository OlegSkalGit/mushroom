@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: Перевірка наявності репозиторію
if not exist ".git" (
    echo [Помилка] Каталог .git не знайдено. Запустіть скрипт із кореня Git-репозиторію.
    pause
    exit /b 1
)

echo [*] Скидання кешу індексу Git (git rm --cached)...
git rm -r --cached . >nul 2>&1

echo [*] Повторне додавання всіх файлів до індексу (git add)...
git add .

:: Отримання повідомлення коміту (з аргументу або через запит)
set "COMMIT_MSG=%~1"
if "%COMMIT_MSG%"=="" (
    set /p "COMMIT_MSG=Введіть повідомлення для коміту (Enter для 'Update and refresh cache'): "
)
if "!COMMIT_MSG!"=="" (
    set "COMMIT_MSG=Update and refresh DB"
)

echo [*] Створення коміту: "!COMMIT_MSG!"
git commit -m "!COMMIT_MSG!"

:: Перевірка чи є що пушити
if %ERRORLEVEL% NEQ 0 (
    echo [i] Немає нових змін для коміту.
    pause
    exit /b 0
)

echo [*] Відправка змін у віддалений репозиторій (git push)...
git push --force origin main

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [✓] Успішно оновлено, закомічено та відправлено на сервер!
) else (
    echo.
    echo [!] Виникла помилка під час виконання git push.
)

pause
