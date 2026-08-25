#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <stdbool.h>
#include <wchar.h>

static bool file_exists(const wchar_t *path) {
    DWORD dwAttrib = GetFileAttributesW(path);
    return (dwAttrib != INVALID_FILE_ATTRIBUTES && !(dwAttrib & FILE_ATTRIBUTE_DIRECTORY));
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR pCmdLine, int nCmdShow) {
    (void)hInstance;
    (void)hPrevInstance;
    (void)pCmdLine;
    (void)nCmdShow;

    wchar_t exePath[MAX_PATH];
    if (GetModuleFileNameW(NULL, exePath, MAX_PATH) == 0) {
        MessageBoxW(NULL, L"실행 파일 경로를 확인할 수 없습니다.", L"Comics8 오류", MB_OK | MB_ICONERROR);
        return 1;
    }

    wchar_t *lastSlash = wcsrchr(exePath, L'\\');
    if (lastSlash) {
        *(lastSlash + 1) = L'\0';
    }

    wchar_t jarPath[MAX_PATH];
    wchar_t jarCandidate1[MAX_PATH];
    wchar_t jarCandidate2[MAX_PATH];

    swprintf(jarCandidate1, MAX_PATH, L"%sapp\\Comics8.jar", exePath);
    swprintf(jarCandidate2, MAX_PATH, L"%sComics8.jar", exePath);

    if (file_exists(jarCandidate1)) {
        wcscpy(jarPath, jarCandidate1);
    } else if (file_exists(jarCandidate2)) {
        wcscpy(jarPath, jarCandidate2);
    } else {
        MessageBoxW(NULL, L"앱 파일(app\\Comics8.jar)을 찾을 수 없습니다.\n압축이 완전히 풀렸는지 확인해 주세요.", L"Comics8 오류", MB_OK | MB_ICONERROR);
        return 1;
    }

    wchar_t javawPath[MAX_PATH];
    wchar_t candidate1[MAX_PATH];
    wchar_t candidate2[MAX_PATH];

    swprintf(candidate1, MAX_PATH, L"%sruntime\\bin\\javaw.exe", exePath);
    swprintf(candidate2, MAX_PATH, L"%sjre\\bin\\javaw.exe", exePath);

    if (file_exists(candidate1)) {
        wcscpy(javawPath, candidate1);
    } else if (file_exists(candidate2)) {
        wcscpy(javawPath, candidate2);
    } else {
        wcscpy(javawPath, L"javaw.exe");
    }

    // Build command line
    wchar_t cmdLine[MAX_PATH * 4];
    swprintf(cmdLine, MAX_PATH * 4, L"\"%s\" -Xmx2048m -jar \"%s\"", javawPath, jarPath);

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_SHOW;
    ZeroMemory(&pi, sizeof(pi));

    BOOL success = CreateProcessW(
        NULL,
        cmdLine,
        NULL,
        NULL,
        FALSE,
        0,
        NULL,
        exePath,
        &si,
        &pi
    );

    if (!success) {
        DWORD err = GetLastError();
        wchar_t errMsg[512];
        if (wcscmp(javawPath, L"javaw.exe") == 0) {
            swprintf(errMsg, 512, L"내장 런타임(runtime)을 찾을 수 없으며 시스템의 Java(javaw) 실행에도 실패했습니다.\n(에러 코드: %lu)", err);
        } else {
            swprintf(errMsg, 512, L"Comics8 실행에 실패했습니다.\n(에러 코드: %lu)", err);
        }
        MessageBoxW(NULL, errMsg, L"Comics8 오류", MB_OK | MB_ICONERROR);
        return 1;
    }

    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return 0;
}
