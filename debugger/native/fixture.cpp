#include <windows.h>
#include <iostream>
extern "C" __declspec(dllexport) __declspec(noinline) int native_calculate(int input) {
    volatile int doubled = input * 2;
    return doubled + 7;
}
static void handled_exception() {
    __try { RaiseException(EXCEPTION_ACCESS_VIOLATION, 0, 0, nullptr); }
    __except (EXCEPTION_EXECUTE_HANDLER) {}
}
extern "C" __declspec(dllexport) volatile long progress_counter = 0;
int main() {
    std::cout << GetCurrentProcessId() << std::endl;
    for (int i = 0;; i++) { handled_exception(); volatile int value = native_calculate(i); (void)value; progress_counter++; Sleep(20); }
}
