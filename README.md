# 🕸️ Weaver Agent

**An autonomous CLI coding agent powered entirely by free-tier cloud AI APIs.**

Weaver is a Kiro-like terminal coding assistant that reads your codebase, writes code, runs commands, searches the web, and finds solutions on Stack Overflow — all autonomously. Just tell it what you want done.

## ⚡ Quick Start

### 1. Prerequisites
- **Java 21** (`java --version`)
- **Maven 3.9+** (`mvn --version`)
- **Docker** (optional, for semantic caching)

### 2. Get Free API Keys (at least one required)

| Provider | Free Tier | Get Key |
|----------|-----------|---------|
| Groq | 14,400 req/day, blazing fast | [console.groq.com](https://console.groq.com) |
| Google Gemini | 1M context, generous limits | [aistudio.google.com](https://aistudio.google.com/apikey) |
| Cerebras | Fastest inference | [cloud.cerebras.ai](https://cloud.cerebras.ai) |
| Mistral | Good reasoning | [console.mistral.ai](https://console.mistral.ai) |
| OpenRouter | Many free models | [openrouter.ai/keys](https://openrouter.ai/keys) |

### 3. Configure API Keys

**Option A: Environment Variables**
```bash
export GROQ_API_KEY=gsk_your_key_here
export GEMINI_API_KEY=your_key_here
```

**Option B: Config File**
```bash
cp configs/application-local.yml configs/my-config.yml
# Edit configs/my-config.yml with your keys
```

### 4. Run

```bash
cd weaver-agent
./mvnw spring-boot:run
```

You'll see:
```
╦ ╦┌─┐┌─┐┬  ┬┌─┐┬─┐
║║║├┤ ├─┤└┐┌┘├┤ ├┬┘
╚╩╝└─┘┴ ┴ └┘ └─┘┴└─
  Autonomous Coding Agent v1.0

  ✓ 3 AI providers ready
  ✓ Primary: groq

weaver>
```

### 5. Use It

```
weaver> Read the pom.xml and tell me what dependencies this project uses

weaver> Create a Python script that reads a CSV and outputs a summary

weaver> Fix the NullPointerException in UserService.java

weaver> Search for how to implement rate limiting in Spring Boot
```

---

## 🏗️ Architecture

```
User Input (terminal or REST API)
        │
        ▼
┌─ Experience Library (Semantic Cache) ─┐
│  ChromaDB + local ONNX embeddings     │
│  If similarity ≥ 0.92 → return cached │
└──────────────┬────────────────────────┘
               │ cache miss
               ▼
┌─ WeaverAgent (ReAct Loop) ────────────┐
│  1. Think: What should I do?          │
│  2. Act: Call a tool                  │
│  3. Observe: Process result           │
│  4. Repeat until done (max 15 steps)  │
└──────────────┬────────────────────────┘
               │
    ┌──────────┼──────────────┐
    ▼          ▼              ▼
┌────────┐ ┌────────┐ ┌─────────────┐
│ Tools  │ │Provider│ │  Fallback   │
│        │ │Registry│ │  Router     │
│• Files │ │        │ │             │
│• Shell │ │ Groq → │ │ 429? retry  │
│• Web   │ │Gemini→ │ │ → next model│
│• SO    │ │Cerebras│ │             │
└────────┘ └────────┘ └─────────────┘
```

## 🔧 Available Tools

| Tool | Description |
|------|-------------|
| `readFile` | Read file contents |
| `readFileLines` | Read specific line range |
| `writeFile` | Create/overwrite a file |
| `editFile` | Surgical string replacement |
| `listDirectory` | Tree view of directory |
| `searchFiles` | Grep-like pattern search |
| `run` / `runCommand` | Execute shell commands |
| `webSearch` | Search DuckDuckGo |
| `fetchWebPage` | Extract text from a URL |
| `searchStackOverflow` | Find code solutions on SO |

## 🔄 Multi-Model Failover

Weaver automatically rotates through providers when rate-limited:

```
Groq (fast, primary) → Gemini (1M context) → Cerebras → Mistral → OpenRouter
```

If a provider returns 429 or errors out, Weaver seamlessly retries with the next provider — **zero interruption, zero cost**.

## 📦 Optional: Semantic Caching

Start ChromaDB for the Experience Library (caches solved problems):

```bash
bash scripts/run_chromadb.sh
```

Without ChromaDB, Weaver works fine — it just won't cache solutions.

## 🌐 REST API (Alternative Interface)

```bash
# Execute a prompt
curl -X POST http://localhost:8080/api/agent/execute \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Create a hello world in Go", "sessionId": "my-session"}'

# Check providers
curl http://localhost:8080/api/agent/providers

# Health check
curl http://localhost:8080/api/agent/health
```

## 📁 Project Structure

```
weaver-agent/
├── pom.xml
├── configs/
│   └── application-local.yml      ← Your API keys go here
├── ai-apis/                       ← Alternative: per-provider key files
├── scripts/
│   ├── run_chromadb.sh            ← Start semantic cache
│   └── weaver_health_check.sh    ← Check all services
└── src/main/java/com/weaver/
    ├── WeaverApplication.java     ← Entry point
    ├── agent/
    │   └── WeaverAgent.java       ← ReAct loop + tool orchestration
    ├── cli/
    │   └── WeaverCli.java         ← Terminal REPL
    ├── api/                       ← REST endpoints
    ├── provider/
    │   └── ProviderRegistry.java  ← Multi-model management
    ├── semantic/
    │   └── ExperienceLibraryService.java  ← Semantic cache
    ├── memory/
    │   └── WeaverMemoryStore.java ← Session-based chat memory
    ├── tools/                     ← All agent tools
    └── config/                    ← Configuration classes
```

## 🎯 CLI Commands

| Command | Description |
|---------|-------------|
| `/help` | Show available commands |
| `/clear` | Clear conversation memory |
| `/new` | Start fresh session |
| `/providers` | List active AI providers |
| `/session` | Show current session ID |
| `/quit` | Exit |

## 💡 Tips

- **Start with Groq** — it's the fastest free API for coding tasks
- **Add Gemini** for tasks involving large files (1M token context)
- **ChromaDB is optional** — skip it initially, add later for caching
- **The agent remembers** within a session — you can say "now fix the test for that" and it knows what "that" refers to
- More providers = more resilience against rate limits

## 📝 License

MIT
