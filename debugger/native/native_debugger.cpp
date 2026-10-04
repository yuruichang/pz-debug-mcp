#define NOMINMAX
#include <windows.h>
#include <dbgeng.h>
#include <wrl/client.h>
#include <iostream>
#include <string>
#include <sstream>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <atomic>
#include <regex>
#include "../vendor/json.hpp"

using json = nlohmann::ordered_json;
using Microsoft::WRL::ComPtr;
struct failure : std::runtime_error {
    std::string code;
    failure(std::string c, std::string m) : std::runtime_error(m), code(std::move(c)) {}
};
static void checked(HRESULT hr, const char* action) {
    if (FAILED(hr)) {
        std::ostringstream text; text << action << " failed: HRESULT 0x" << std::hex << static_cast<unsigned long>(hr);
        throw failure("NATIVE_ENGINE", text.str());
    }
}
static std::string hex(ULONG64 value) {
    std::ostringstream text; text << "0x" << std::hex << value; return text.str();
}
static std::string utf8(const wchar_t* value) {
    if (!value || !*value) return "";
    int size = WideCharToMultiByte(CP_UTF8, 0, value, -1, nullptr, 0, nullptr, nullptr);
    std::string out(size, '\0');
    WideCharToMultiByte(CP_UTF8, 0, value, -1, out.data(), size, nullptr, nullptr);
    out.resize(size - 1); return out;
}
static std::wstring wide(const std::string& value) {
    int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), nullptr, 0);
    if (!size) throw failure("ARGUMENT", "Invalid UTF-8 symbol name");
    std::wstring out(size, L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), out.data(), size);
    return out;
}
static std::string bytes_hex(const unsigned char* bytes, size_t size) {
    static const char* digits = "0123456789abcdef"; std::string out;
    for (size_t i = 0; i < size; i++) { out += digits[bytes[i] >> 4]; out += digits[bytes[i] & 15]; }
    return out;
}
static ULONG64 address(const json& args, const char* name) {
    const auto text = args.value(name, std::string("0"));
    size_t used = 0; ULONG64 value = std::stoull(text, &used, 0);
    if (used != text.size() || value == 0) throw failure("ARGUMENT", "A nonzero hexadecimal address is required");
    return value;
}
class Events final : public IDebugEventCallbacks {
    std::atomic<ULONG> refs{1};
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IDebugEventCallbacks)) { *out = this; AddRef(); return S_OK; }
        *out = nullptr; return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs; }
    ULONG STDMETHODCALLTYPE Release() override { ULONG n = --refs; if (!n) delete this; return n; }
    HRESULT STDMETHODCALLTYPE GetInterestMask(PULONG mask) override {
        *mask = DEBUG_EVENT_BREAKPOINT | DEBUG_EVENT_EXCEPTION; return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Breakpoint(PDEBUG_BREAKPOINT) override { return DEBUG_STATUS_BREAK; }
    HRESULT STDMETHODCALLTYPE Exception(PEXCEPTION_RECORD64 record, ULONG first) override {
        return (!first || record->ExceptionCode == EXCEPTION_BREAKPOINT || record->ExceptionCode == EXCEPTION_SINGLE_STEP)
            ? DEBUG_STATUS_BREAK : DEBUG_STATUS_GO_NOT_HANDLED;
    }
    HRESULT STDMETHODCALLTYPE CreateThread(ULONG64, ULONG64, ULONG64) override { return DEBUG_STATUS_NO_CHANGE; }
    HRESULT STDMETHODCALLTYPE ExitThread(ULONG) override { return DEBUG_STATUS_NO_CHANGE; }
    HRESULT STDMETHODCALLTYPE CreateProcess(ULONG64, ULONG64, ULONG64, ULONG, PCSTR, PCSTR, ULONG, ULONG, ULONG64, ULONG64, ULONG64) override { return DEBUG_STATUS_NO_CHANGE; }
    HRESULT STDMETHODCALLTYPE ExitProcess(ULONG) override { return DEBUG_STATUS_NO_CHANGE; }
    HRESULT STDMETHODCALLTYPE LoadModule(ULONG64, ULONG64, ULONG, PCSTR, PCSTR, ULONG, ULONG) override { return DEBUG_STATUS_NO_CHANGE; }
    HRESULT STDMETHODCALLTYPE UnloadModule(PCSTR, ULONG64) override { return DEBUG_STATUS_NO_CHANGE; }
    HRESULT STDMETHODCALLTYPE SystemError(ULONG, ULONG) override { return DEBUG_STATUS_NO_CHANGE; }
    HRESULT STDMETHODCALLTYPE SessionStatus(ULONG) override { return DEBUG_STATUS_NO_CHANGE; }
    HRESULT STDMETHODCALLTYPE ChangeDebuggeeState(ULONG, ULONG64) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE ChangeEngineState(ULONG, ULONG64) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE ChangeSymbolState(ULONG, ULONG64) override { return S_OK; }
};
class EngineOutput final : public IDebugOutputCallbacks {
    std::atomic<ULONG> refs{1};
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid, void** out) override {
        if (iid == __uuidof(IUnknown) || iid == __uuidof(IDebugOutputCallbacks)) { *out = this; AddRef(); return S_OK; }
        *out = nullptr; return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return ++refs; }
    ULONG STDMETHODCALLTYPE Release() override { ULONG n = --refs; if (!n) delete this; return n; }
    HRESULT STDMETHODCALLTYPE Output(ULONG, PCSTR text) override { std::cerr << text; return S_OK; }
};
class Engine {
    ComPtr<IDebugClient> client;
    ComPtr<IDebugControl> control;
    ComPtr<IDebugSymbols3> symbols;
    ComPtr<IDebugSystemObjects> systems;
    ComPtr<IDebugDataSpaces2> memory;
    ComPtr<IDebugRegisters> registers;
    bool attached = false;
    bool waiting = false;
    ULONG target = 0;
    std::string last_error;
    std::map<ULONG, json> owned_breakpoints;
    void remember(IDebugBreakpoint* bp, ULONG64 at) {
        unsigned char bytes[16]{}; ULONG read = 0, id = 0;
        checked(memory->ReadVirtual(at, bytes, sizeof(bytes), &read), "Original breakpoint bytes");
        if (read != sizeof(bytes)) throw failure("BREAKPOINT_MEMORY", "Cannot capture complete breakpoint bytes");
        checked(bp->GetId(&id), "Breakpoint ID");
        owned_breakpoints[id] = {{"breakpoint_id", id}, {"address", hex(at)}, {"original_hex", bytes_hex(bytes, read)}};
    }
    void require() {
        if (!attached) throw failure("NATIVE_DISCONNECTED", "Attach a target process first");
    }
    void stopped() {
        require(); ULONG state;
        checked(control->GetExecutionStatus(&state), "GetExecutionStatus");
        if (waiting || state != DEBUG_STATUS_BREAK) throw failure("NOT_PAUSED", "Pause native execution before this operation");
    }
    std::string symbol(ULONG64 offset) {
        wchar_t buffer[2048]{}; ULONG64 displacement;
        if (SUCCEEDED(symbols->GetNameByOffsetWide(offset, buffer, 2048, nullptr, &displacement)))
            return utf8(buffer) + (displacement ? "+" + hex(displacement) : "");
        return "";
    }
public:
    Engine() {
        wchar_t system[MAX_PATH]{};
        GetSystemDirectoryW(system, MAX_PATH);
        std::wstring dbghelp = std::wstring(system) + L"\\dbghelp.dll";
        if (!LoadLibraryExW(dbghelp.c_str(), nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32))
            throw failure("SYMBOL_ENGINE_UNAVAILABLE", "Windows DbgHelp could not be loaded");
        checked(DebugCreate(__uuidof(IDebugClient), reinterpret_cast<void**>(client.GetAddressOf())), "DebugCreate");
        checked(client.As(&control), "IDebugControl");
        checked(client.As(&symbols), "IDebugSymbols3");
        checked(client.As(&systems), "IDebugSystemObjects");
        checked(client.As(&memory), "IDebugDataSpaces");
        checked(client.As(&registers), "IDebugRegisters");
        ComPtr<IDebugEventCallbacks> callbacks;
        callbacks.Attach(new Events());
        checked(client->SetEventCallbacks(callbacks.Get()), "SetEventCallbacks");
        ComPtr<IDebugOutputCallbacks> output;
        output.Attach(new EngineOutput());
        checked(client->SetOutputCallbacks(output.Get()), "SetOutputCallbacks");
        checked(control->AddEngineOptions(DEBUG_ENGOPT_INITIAL_BREAK), "Initial break option");
        ULONG events = 0, exceptions = 0, arbitrary = 0;
        checked(control->GetNumberEventFilters(&events, &exceptions, &arbitrary), "Event filters");
        std::vector<DEBUG_EXCEPTION_FILTER_PARAMETERS> filters(exceptions + arbitrary);
        checked(control->GetExceptionFilterParameters(static_cast<ULONG>(filters.size()), nullptr, events, filters.data()), "Exception filters");
        for (auto& filter : filters) {
            if (filter.ExceptionCode == EXCEPTION_BREAKPOINT || filter.ExceptionCode == EXCEPTION_SINGLE_STEP) continue;
            filter.ExecutionOption = DEBUG_FILTER_SECOND_CHANCE_BREAK;
            filter.ContinueOption = DEBUG_FILTER_GO_NOT_HANDLED;
        }
        checked(control->SetExceptionFilterParameters(static_cast<ULONG>(filters.size()), filters.data()), "First-chance handling");
    }
    ~Engine() { detach(); }
    void detach() {
        if (attached) {
            // DbgEng removes its breakpoint patches and resumes the target when detaching.
            client->DetachProcesses();
            attached = false; target = 0;
            waiting = false;
            owned_breakpoints.clear();
        }
    }
    void poll() {
        if (!attached) return;
        ULONG state;
        HRESULT hr = control->GetExecutionStatus(&state);
        if (FAILED(hr)) { last_error = "TARGET_DISCONNECTED"; detach(); return; }
        if (waiting) {
            hr = control->WaitForEvent(DEBUG_WAIT_DEFAULT, 50);
            if (hr == S_OK) waiting = false;
            else if (FAILED(hr) && hr != E_PENDING && hr != HRESULT_FROM_WIN32(WAIT_TIMEOUT)) {
                last_error = "WAIT_EVENT_FAILED"; detach(); return;
            }
        }
        ULONG processes = 0;
        if (SUCCEEDED(systems->GetNumberProcesses(&processes)) && processes == 0) {
            last_error = "TARGET_EXITED"; attached = false; target = 0;
        }
    }
    json status() {
        json breakpoints = json::array();
        for (auto it = owned_breakpoints.begin(); it != owned_breakpoints.end();) {
            IDebugBreakpoint* bp = nullptr;
            if (!attached || FAILED(control->GetBreakpointById(it->first, &bp))) it = owned_breakpoints.erase(it);
            else { breakpoints.push_back(it->second); ++it; }
        }
        ULONG state = DEBUG_STATUS_NO_DEBUGGEE;
        if (attached) control->GetExecutionStatus(&state);
        ULONG type = 0, pid = 0, tid = 0, needed = 0;
        char description[2048]{};
        if (attached) control->GetLastEventInformation(&type, &pid, &tid, nullptr, 0, nullptr,
            description, sizeof(description), &needed);
        return {{"connected", attached}, {"target_pid", target}, {"paused", !waiting && state == DEBUG_STATUS_BREAK},
            {"execution_status", state}, {"last_error", last_error}, {"breakpoints", breakpoints}, {"last_event", {
            {"type", type}, {"process_id", pid}, {"thread_id", tid}, {"description", description}}}};
    }
    json execute(const std::string& op, const json& args) {
        if (op == "status") { poll(); return status(); }
        if (op == "detach" || op == "close") { detach(); return status(); }
        if (op == "attach") {
            detach(); target = args.at("pid").get<ULONG>();
            if (target == GetCurrentProcessId() || target == 0) throw failure("ARGUMENT", "A different target process is required");
            HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, target);
            if (process) {
                wchar_t path[32768]{}; DWORD size = 32768;
                if (QueryFullProcessImageNameW(process, 0, path, &size)) {
                    std::wstring directory(path);
                    directory.resize(directory.find_last_of(L"\\/"));
                    HRESULT hr = symbols->SetSymbolPathWide(directory.c_str());
                    if (FAILED(hr)) { CloseHandle(process); checked(hr, "Local symbol path"); }
                }
                CloseHandle(process);
            }
            checked(client->AttachProcess(0, target, DEBUG_ATTACH_DEFAULT), "AttachProcess");
            attached = true; last_error.clear();
            waiting = true;
            HRESULT hr = control->WaitForEvent(DEBUG_WAIT_DEFAULT, 5000);
            if (hr != S_OK) { detach(); throw failure("ATTACH_TIMEOUT", "Initial native attach event did not arrive"); }
            waiting = false;
            try { checked(client->SetProcessOptions(DEBUG_PROCESS_DETACH_ON_EXIT), "SetProcessOptions"); }
            catch (...) { detach(); throw; }
            return status();
        }
        require();
        if (op == "pause") {
            ULONG state; checked(control->GetExecutionStatus(&state), "GetExecutionStatus");
            if (waiting || state != DEBUG_STATUS_BREAK) {
                checked(control->SetInterrupt(DEBUG_INTERRUPT_ACTIVE), "SetInterrupt");
                HRESULT hr = control->WaitForEvent(DEBUG_WAIT_DEFAULT, 5000);
                if (hr != S_OK) throw failure("PAUSE_TIMEOUT", "Native pause event did not arrive");
                waiting = false;
            }
            return status();
        }
        if (op == "resume" || op == "step") {
            stopped();
            ULONG next = DEBUG_STATUS_GO;
            if (op == "step") {
                if (args.contains("thread_id")) checked(systems->SetCurrentThreadId(args.at("thread_id").get<ULONG>()), "Step thread");
                auto depth = args.value("depth", std::string("into"));
                if (depth == "into") next = DEBUG_STATUS_STEP_INTO;
                else if (depth == "over") next = DEBUG_STATUS_STEP_OVER;
                else if (depth == "out") {
                    DEBUG_STACK_FRAME frame{}; ULONG count = 0;
                    checked(control->GetStackTrace(0, 0, 0, &frame, 1, &count), "Return frame");
                    if (!count || !frame.ReturnOffset) throw failure("RETURN_ADDRESS_UNAVAILABLE", "Return address could not be unwound");
                    ComPtr<IDebugBreakpoint> bp;
                    checked(control->AddBreakpoint(DEBUG_BREAKPOINT_CODE, DEBUG_ANY_ID, bp.GetAddressOf()), "Step-out breakpoint");
                    try {
                        checked(bp->SetOffset(frame.ReturnOffset), "Return address");
                        ULONG tid; checked(systems->GetCurrentThreadId(&tid), "Current thread");
                        checked(bp->SetMatchThreadId(tid), "Match thread");
                        remember(bp.Get(), frame.ReturnOffset);
                        checked(bp->AddFlags(DEBUG_BREAKPOINT_ENABLED | DEBUG_BREAKPOINT_ONE_SHOT), "Enable return breakpoint");
                    } catch (...) { auto ptr = bp.Detach(); control->RemoveBreakpoint(ptr); throw; }
                    next = DEBUG_STATUS_GO;
                }
                else throw failure("ARGUMENT", "Native step depth is into/over/out");
            }
            checked(control->SetExecutionStatus(next), "SetExecutionStatus");
            waiting = true;
            return status();
        }
        if (op == "modules") {
            stopped(); ULONG loaded, unloaded;
            checked(symbols->GetNumberModules(&loaded, &unloaded), "GetNumberModules");
            json out = json::array();
            const ULONG offset = args.value("offset", 0u), limit = std::min(args.value("limit", 50u), 100u);
            for (ULONG i = offset; i < loaded && out.size() < limit; i++) {
                ULONG64 base; checked(symbols->GetModuleByIndex(i, &base), "GetModuleByIndex");
                DEBUG_MODULE_PARAMETERS params{};
                checked(symbols->GetModuleParameters(1, &base, 0, &params), "GetModuleParameters");
                wchar_t image[4096]{}, name[1024]{};
                symbols->GetModuleNameStringWide(DEBUG_MODNAME_IMAGE, i, base, image, 4096, nullptr);
                symbols->GetModuleNameStringWide(DEBUG_MODNAME_MODULE, i, base, name, 1024, nullptr);
                out.push_back({{"module", utf8(name)}, {"image", utf8(image)}, {"base", hex(base)}, {"size", params.Size},
                    {"symbol_type", params.SymbolType}, {"pdb_available", params.SymbolType == DEBUG_SYMTYPE_DEFERRED ?
                        json(nullptr) : json(params.SymbolType == DEBUG_SYMTYPE_PDB)}});
            }
            return {{"total", loaded}, {"items", out}, {"unloaded_modules", unloaded}};
        }
        if (op == "threads") {
            stopped(); ULONG count; checked(systems->GetNumberThreads(&count), "GetNumberThreads");
            std::vector<ULONG> ids(count), osids(count);
            checked(systems->GetThreadIdsByIndex(0, count, ids.data(), osids.data()), "GetThreadIds");
            json out = json::array();
            for (ULONG i = 0; i < count; i++) out.push_back({{"thread_id", ids[i]}, {"os_thread_id", osids[i]}});
            return {{"threads", out}};
        }
        if (op == "symbols") {
            stopped();
            auto pattern = args.value("pattern", std::string("*"));
            if (pattern.size() > 512 || pattern.find('\n') != std::string::npos) throw failure("ARGUMENT", "Invalid symbol pattern");
            ULONG64 match; checked(symbols->StartSymbolMatchWide(wide(pattern).c_str(), &match), "StartSymbolMatch");
            json out = json::array();
            try {
                for (ULONG i = 0; i < std::min(args.value("limit", 50u), 100u); i++) {
                    wchar_t name[2048]{}; ULONG64 at;
                    if (FAILED(symbols->GetNextSymbolMatchWide(match, name, 2048, nullptr, &at))) break;
                    out.push_back({{"symbol", utf8(name)}, {"address", hex(at)}});
                }
            } catch (...) { symbols->EndSymbolMatch(match); throw; }
            symbols->EndSymbolMatch(match); return {{"symbols", out}};
        }
        if (op == "stack" || op == "registers" || op == "locals") {
            stopped();
            ULONG original; checked(systems->GetCurrentThreadId(&original), "Current thread");
            ULONG originalFrame = 0; symbols->GetCurrentScopeFrameIndex(&originalFrame);
            const ULONG tid = args.value("thread_id", original);
            checked(systems->SetCurrentThreadId(tid), "Select thread");
            try {
                json result;
                if (op == "stack") {
                    DEBUG_STACK_FRAME frames[64]{}; ULONG count;
                    checked(control->GetStackTrace(0, 0, 0, frames, std::min(args.value("limit", 32u), 64u), &count), "GetStackTrace");
                    json out = json::array();
                    for (ULONG i = 0; i < count; i++) out.push_back({{"frame", i}, {"instruction", hex(frames[i].InstructionOffset)},
                        {"return_address", hex(frames[i].ReturnOffset)}, {"stack", hex(frames[i].StackOffset)},
                        {"symbol", symbol(frames[i].InstructionOffset)}});
                    result = {{"frames", out}};
                } else if (op == "registers") {
                    ULONG count; checked(registers->GetNumberRegisters(&count), "GetNumberRegisters");
                    json out = json::array();
                    for (ULONG i = 0; i < count && i < 128; i++) {
                        char name[256]{}; DEBUG_REGISTER_DESCRIPTION desc{}; DEBUG_VALUE value{};
                        registers->GetDescription(i, name, sizeof(name), nullptr, &desc);
                        if (SUCCEEDED(registers->GetValue(i, &value))) {
                            json item = {{"name", name}, {"type", value.Type}};
                            if (value.Type >= DEBUG_VALUE_INT8 && value.Type <= DEBUG_VALUE_INT64) {
                                ULONG64 integer = value.Type == DEBUG_VALUE_INT64 ? value.I64 :
                                    value.Type == DEBUG_VALUE_INT32 ? value.I32 : value.Type == DEBUG_VALUE_INT16 ? value.I16 : value.I8;
                                item["value"] = hex(integer);
                            } else {
                                const size_t sizes[] = {0, 1, 2, 4, 8, 4, 8, 10, 11, 16, 8, 16};
                                item["raw_hex"] = bytes_hex(value.RawBytes, value.Type <= DEBUG_VALUE_VECTOR128 ? sizes[value.Type] : sizeof(value.RawBytes));
                                if (value.Type == DEBUG_VALUE_FLOAT32) item["value"] = value.F32;
                                if (value.Type == DEBUG_VALUE_FLOAT64) item["value"] = value.F64;
                            }
                            out.push_back(item);
                        }
                    }
                    result = {{"registers", out}};
                } else {
                    const ULONG frame = args.value("frame", 0u);
                    checked(symbols->SetScopeFrameByIndex(frame), "SetScopeFrame");
                    ComPtr<IDebugSymbolGroup2> group;
                    HRESULT hr = symbols->GetScopeSymbolGroup2(DEBUG_SCOPE_GROUP_LOCALS, nullptr, group.GetAddressOf());
                    if (FAILED(hr)) result = {{"locals_available", false}, {"reason", "Private PDB/debug symbols are required"}, {"locals", json::array()}};
                    else {
                        ULONG count; checked(group->GetNumberSymbols(&count), "GetNumberSymbols");
                        json out = json::array();
                        for (ULONG i = 0; i < count && out.size() < std::min(args.value("limit", 32u), 100u); i++) {
                            wchar_t name[1024]{}, type[1024]{}, value[4096]{};
                            group->GetSymbolNameWide(i, name, 1024, nullptr);
                            group->GetSymbolTypeNameWide(i, type, 1024, nullptr);
                            group->GetSymbolValueTextWide(i, value, 4096, nullptr);
                            out.push_back({{"name", utf8(name)}, {"type", utf8(type)}, {"value", utf8(value)}});
                        }
                        bool hasSymbols = count > 0;
                        if (!hasSymbols) {
                            DEBUG_STACK_FRAME frames[64]{}; ULONG framesCount = 0;
                            if (SUCCEEDED(control->GetStackTrace(0, 0, 0, frames, 64, &framesCount)) && frame < framesCount) {
                                ULONG64 base = 0; ULONG index = 0; DEBUG_MODULE_PARAMETERS params{};
                                if (SUCCEEDED(symbols->GetModuleByOffset(frames[frame].InstructionOffset, 0, &index, &base)) &&
                                    SUCCEEDED(symbols->GetModuleParameters(1, &base, 0, &params))) hasSymbols = params.SymbolType == DEBUG_SYMTYPE_PDB;
                            }
                        }
                        result = {{"locals_available", hasSymbols}, {"locals", out}};
                        if (!hasSymbols) result["reason"] = "Private PDB/debug symbols are required";
                    }
                }
                systems->SetCurrentThreadId(original); symbols->SetScopeFrameByIndex(originalFrame); return result;
            } catch (...) { systems->SetCurrentThreadId(original); symbols->SetScopeFrameByIndex(originalFrame); throw; }
        }
        if (op == "breakpoint_add") {
            stopped();
            ULONG64 at;
            if (args.contains("symbol")) checked(symbols->GetOffsetByNameWide(wide(args.at("symbol").get<std::string>()).c_str(), &at), "Resolve symbol");
            else at = address(args, "address");
            ComPtr<IDebugBreakpoint> bp;
            checked(control->AddBreakpoint(DEBUG_BREAKPOINT_CODE, DEBUG_ANY_ID, bp.GetAddressOf()), "AddBreakpoint");
            try {
                MEMORY_BASIC_INFORMATION64 region{};
                checked(memory->QueryVirtual(at, &region), "Breakpoint region");
                if (!(region.Protect & (PAGE_EXECUTE | PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY)))
                    throw failure("NOT_EXECUTABLE", "Breakpoint address is not executable code");
                checked(bp->SetOffset(at), "SetOffset"); remember(bp.Get(), at);
                checked(bp->AddFlags(DEBUG_BREAKPOINT_ENABLED), "Enable breakpoint");
            } catch (...) { auto ptr = bp.Detach(); control->RemoveBreakpoint(ptr); throw; }
            ULONG id; checked(bp->GetId(&id), "GetId");
            auto out = status(); out["breakpoint_id"] = id; out["address"] = hex(at); out["symbol"] = symbol(at); return out;
        }
        if (op == "breakpoint_remove") {
            stopped(); ComPtr<IDebugBreakpoint> bp;
            checked(control->GetBreakpointById(args.at("breakpoint_id").get<ULONG>(), bp.GetAddressOf()), "GetBreakpoint");
            auto ptr = bp.Detach();
            checked(control->RemoveBreakpoint(ptr), "RemoveBreakpoint");
            owned_breakpoints.erase(args.at("breakpoint_id").get<ULONG>()); return status();
        }
        if (op == "memory") {
            stopped(); const ULONG bytes = args.value("bytes", 64u);
            if (bytes == 0 || bytes > 65536) throw failure("ARGUMENT", "Memory reads must be 1..65536 bytes");
            const ULONG64 at = address(args, "address"); std::vector<unsigned char> data(bytes); ULONG read;
            checked(memory->ReadVirtual(at, data.data(), bytes, &read), "ReadVirtual");
            return {{"address", hex(at)}, {"bytes", read}, {"hex", bytes_hex(data.data(), read)}};
        }
        throw failure("UNKNOWN_OPERATION", "Unsupported native debugging operation");
    }
};
int main() {
    std::mutex mutex; std::condition_variable ready; std::queue<std::string> requests; std::atomic<bool> eof = false;
    std::thread reader([&] {
        std::string line;
        while (std::getline(std::cin, line)) {
            std::lock_guard<std::mutex> lock(mutex);
            if (line.size() > 65536 || requests.size() >= 64) { eof = true; break; }
            requests.push(line); ready.notify_one();
        }
        eof = true; ready.notify_one();
    });
    try {
        Engine engine;
        while (true) {
            std::string line;
            {
                std::unique_lock<std::mutex> lock(mutex);
                if (eof && requests.empty()) break;
                ready.wait_for(lock, std::chrono::milliseconds(25), [&] { return eof || !requests.empty(); });
                if (!requests.empty()) { line = requests.front(); requests.pop(); }
            }
            if (line.empty()) { engine.poll(); continue; }
            json response; std::string id;
            try {
                auto request = json::parse(line);
                id = request.at("id").get<std::string>();
                if (!std::regex_match(id, std::regex("[a-f0-9]{32}")) || request.value("protocol", 0) != 1)
                    throw failure("PROTOCOL", "Invalid debugger request");
                auto result = engine.execute(request.at("operation").get<std::string>(), request.at("arguments"));
                response = {{"protocol", 1}, {"id", id}, {"ok", true}, {"result", result}};
            } catch (const std::exception& error) {
                auto f = dynamic_cast<const failure*>(&error);
                response = {{"protocol", 1}, {"id", id}, {"ok", false},
                    {"error", {{"code", f ? f->code : "NATIVE_ERROR"}, {"message", error.what()}}}};
            }
            std::cout << response.dump(-1, ' ', false, json::error_handler_t::replace) << std::endl;
        }
        engine.detach();
    } catch (const std::exception& error) {
        std::cerr << error.what() << std::endl;
        ExitProcess(2);
    }
    reader.join();
    return 0;
}
