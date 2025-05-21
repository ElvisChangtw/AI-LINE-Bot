# AI-LINE-Bot

> A general‑purpose LINE chatbot built with **Spring Boot 3**, **Spring AI**, and **OpenAI**. It supports natural‑language chat, Whisper voice transcription, `/imagine` image generation, and ships in a slim multi‑stage **Docker** image—ready for Render, Fly.io, Railway, or any Docker‑capable host.

---

## ✨ Features

| Capability          | Details                                                                                  |
| ------------------- | ---------------------------------------------------------------------------------------- |
| 💬 Chat & Q\&A      | Sends user messages to OpenAI and streams concise answers.                               |
| 🎙️ Voice messages  | Converts LINE audio messages to text with **Whisper** and routes them through chat.      |
| 🎨 Image generation | `/imagine <prompt>` → DALL·E 3 (standard) or GPT‑Image‑1 (when prompt contains **高畫質**). |
| 🐳 Container ready  | Multi‑stage Dockerfile (Java 21) → < 200 MB runtime image.                               |
| 🚀 One‑click deploy | Works out‑of‑the‑box on any platform that builds a Dockerfile.                           |

---

## 🏗️ Tech Stack

* **Java 21**, **Spring Boot 3.3**
* **Spring AI 1.0.0‑M8** (Chat, Image, Audio)
* **LINE Bot SDK 9.7**
* **Maven 3.9.6**
* **Docker** & **Compose V2**

---

## 🚀 Quick Start (Docker‑only)

### 1. Clone & create `.env`

```bash
git clone https://github.com/<your-name>/AI-LINE-Bot.git
cd AI-LINE-Bot
cp .env.example .env   # or create manually
```

#### `.env` format

```env
# ▶ LINE credentials
LINE_CHANNEL_TOKEN=YOUR_LINE_CHANNEL_TOKEN
LINE_CHANNEL_SECRET=YOUR_LINE_CHANNEL_SECRET

# ▶ OpenAI credentials & tuning
OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
OPEN_AI_MODEL=gpt-4o           # e.g. gpt-4o, gpt-3.5-turbo, etc.
OPEN_AI_TEMPERATURE=0.7        # 0 – 2

# ▶ Optional Spring profile / port overrides
SPRING_PROFILES_ACTIVE=prod
PORT=8080                      # matches Dockerfile EXPOSE
```

> **Keep `.env` private!** It is ignored by Git via `.gitignore`.

### 2. Build the image

```bash
docker build -t ai-line-bot .
```

### 3. Run the container

```bash
docker run -d --name ai-line-bot \
  --env-file .env \
  -p 8080:8080 \
  ai-line-bot
```

### 4. (Optional) Use Docker Compose for local dev

`docker-compose.yml` (Compose V2 syntax):

```yaml
services:
  line-bot:
    build: .
    ports:
      - "8080:8080"
    env_file:
      - .env
```

```bash
docker compose up --build
```

---

## ☁️ Deploying to Render

1. **Create → Web Service** → choose **Docker** environment.
2. Connect the repository; Render auto‑detects `Dockerfile`.
3. Add the variables from `.env` in **Environment** settings.
4. Set **Port** to `8080`.
5. Create the service—Render handles the rest.

Render ignores `docker-compose.yml`; only the `Dockerfile` is required.

---

## 🔌 API Endpoints

| Method | Path        | Purpose                                                                             |
| ------ | ----------- | ----------------------------------------------------------------------------------- |
| `GET`  | `/hello`    | Health check                                                                        |
| `POST` | `/callback` | LINE webhook – **must** match the URL set in the LINE console (append `/callback`). |

---

## 🛠️ Customising Bot Behaviour
Adjust the `openai.system-prompt` in `application.yml` to give the bot a different persona or rules, and tweak temperature/model through `.env`.
---
