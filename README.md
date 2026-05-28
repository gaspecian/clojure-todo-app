# clojure-todo-app

> A production-shaped Clojure app built as a learning exercise — because the best way to understand how Clojure thinks about web development is to build something real.

## What is this?

This is a full-stack monorepo built to explore Clojure by doing something non-trivial. It has a Clojure REST API that acts as both an OAuth 2.0 authorization server and a todo resource server, a React frontend that consumes it, MongoDB for persistence, Docker for local development, and CI pipelines. Not a toy — but also not so large that you can't read every line.

The Clojure backend is where the interesting learning happens. It implements a real [OAuth 2.0 Authorization Code + PKCE](docs/OAUTH_FLOW.md) flow from scratch, handles authentication with [JWT](https://jwt.io/) and [bcrypt](https://en.wikipedia.org/wiki/Bcrypt), and stores data in [MongoDB](https://www.mongodb.com/). The React frontend exists to show how that auth server is consumed from a real client — it's not the main focus.

This project was built as a learning exercise. It makes the design decisions explicit, explains the patterns it uses, and links out to deeper resources rather than trying to teach everything inline.

## Why this project?

If you know another language and want to understand how Clojure approaches web development, most tutorials stop at "here's a handler that returns Hello World." This project doesn't.

In Clojure, HTTP handlers are pure functions. Routing is data. Middleware is function composition. Configuration is a data file. These aren't buzzwords — they're concrete patterns that change how you structure an application, and they're all present here in working code.

The patterns you'll see — [Ring](https://github.com/ring-clojure/ring), [Reitit](https://github.com/metosin/reitit), data-driven design — are how real Clojure web apps are built. The goal isn't to teach syntax. It's to show how Clojure *thinks* about web applications, and to give you a codebase small enough to hold in your head while doing it.

You can run the full stack locally with two commands. Each subsystem has its own doc. Go as deep or as shallow as you want.

## Tech stack

### Clojure API (`apps/api`)

| Library | Role |
|---------|------|
| [Clojure 1.12](https://clojure.org/) | The language |
| [Ring](https://github.com/ring-clojure/ring) | HTTP server abstraction — handlers are plain functions |
| [Reitit](https://github.com/metosin/reitit) | Data-driven router — routes are just data |
| [Muuntaja](https://github.com/metosin/muuntaja) | JSON content negotiation middleware |
| [Buddy](https://github.com/funcool/buddy) | JWT signing (`buddy-sign`) and bcrypt hashing (`buddy-hashers`) |
| [Monger](https://github.com/michaelklishin/monger) | MongoDB client |
| [Aero](https://github.com/juxt/aero) | EDN-based config with env var support |
| [tools.build](https://github.com/clojure/tools.build) | Uberjar builds |

### React frontend (`apps/web`)

| Library | Role |
|---------|------|
| [React 19](https://react.dev/) + [Vite 6](https://vite.dev/) | UI framework and build tool |
| [TanStack Query v5](https://tanstack.com/query/latest) | Server state management and silent token refresh |
| [ShadCN UI](https://ui.shadcn.com/) + [Tailwind CSS](https://tailwindcss.com/) | Component library and styling |

### Infrastructure & tooling

| Tool | Role |
|------|------|
| [MongoDB 7](https://www.mongodb.com/) | Database (runs in Docker locally) |
| [pnpm workspaces](https://pnpm.io/workspaces) + [Turborepo](https://turbo.build/) | Monorepo tooling — `turbo dev` starts both apps in parallel |
| [GitHub Actions](https://github.com/features/actions) | CI pipelines for both apps, path-filtered |

## Project structure

```
clojure-todo-app/
├── package.json               # pnpm workspace root — defines dev/build/test scripts
├── pnpm-workspace.yaml        # declares apps/* as workspace packages
├── turbo.json                 # Turborepo pipeline — turbo dev starts both apps in parallel
├── docker-compose.yml         # local MongoDB + full prod stack (--profile prod)
├── .env.example               # all environment variables documented with defaults
├── .github/
│   └── workflows/
│       ├── api-ci.yml         # Clojure CI: clj-kondo lint, nvd check, tests
│       └── web-ci.yml         # React CI: ESLint, tsc, pnpm audit
├── apps/
│   ├── api/                   # Clojure REST API + OAuth 2.0 authorization server
│   │   ├── src/api/
│   │   │   ├── core.clj       # server entry point — assembles router and middleware stack
│   │   │   ├── oauth/         # OAuth 2.0 handlers (authorize, token, revoke, userinfo)
│   │   │   ├── auth/          # signup and login handlers + server-rendered login page
│   │   │   ├── todos/         # todo CRUD handlers (list, create, get, update, delete)
│   │   │   ├── db/            # MongoDB connection and seed data
│   │   │   └── middleware/    # JWT auth middleware — validates Bearer tokens
│   │   ├── resources/
│   │   │   ├── config.edn     # Aero config — reads PORT, MONGODB_URI, JWT_SECRET from env
│   │   │   └── templates/     # server-rendered login HTML (served by the Clojure process)
│   │   ├── test/              # handler tests — 17 tests, no mocks, real MongoDB
│   │   ├── deps.edn           # Clojure's equivalent of package.json — deps and aliases
│   │   ├── build.clj          # tools.build uberjar script (clj -T:build uber)
│   │   └── Dockerfile         # multi-stage build: uberjar → JRE Alpine
│   └── web/                   # React SPA — OAuth client, todo UI
│       ├── src/
│       │   ├── pages/         # Login redirect, Signup, Callback, Todos
│       │   ├── components/    # ShadCN component wrappers
│       │   ├── hooks/         # useTodos — TanStack Query mutations and queries
│       │   ├── lib/           # oauth.ts (PKCE helpers), api.ts (fetch + token refresh)
│       │   └── contexts/      # AuthContext — global auth state
│       ├── nginx.conf         # SPA routing fallback + static asset caching + security headers
│       └── Dockerfile         # multi-stage build: Vite build → nginx Alpine
└── docs/
    ├── ARCHITECTURE.md        # system overview, request lifecycle, design decisions
    └── OAUTH_FLOW.md          # full Authorization Code + PKCE flow explained
```

## Quick start

### Prerequisites

You'll need the following installed before you begin:

| Tool | Version | Install |
|------|---------|---------|
| JDK | 21+ | [Adoptium](https://adoptium.net/) |
| Clojure CLI | latest | [clojure.org/guides/install_clojure](https://clojure.org/guides/install_clojure) |
| Node.js | 22+ | [nodejs.org](https://nodejs.org/) |
| pnpm | 10+ | `npm install -g pnpm` |
| Docker | latest | [docs.docker.com/get-docker](https://docs.docker.com/get-docker/) |

### 1. Clone and install dependencies

```bash
git clone https://github.com/gaspecian/clojure-todo-app.git
cd clojure-todo-app
pnpm install
```

`pnpm install` installs dependencies for the entire monorepo at once — both `apps/api` (Node tooling) and `apps/web` share a single `node_modules` via pnpm workspaces.

### 2. Set up environment variables

```bash
cp .env.example .env
```

`.env.example` documents every environment variable the project uses. For local development, the defaults work as-is — you don't need to change anything. The one exception is `JWT_SECRET`: the default is intentionally weak and must be replaced before exposing the app to anyone else.

### 3. Start MongoDB

```bash
docker compose up -d
```

This starts a MongoDB 7 container in the background on port `27017`. Using Docker avoids a local MongoDB installation and keeps the database state in a named volume (`mongo_data`) that persists across restarts. Verify it started cleanly:

```bash
docker compose ps
```

Expected: `mongodb` service shows `running (healthy)`.

### 4. Start both apps

```bash
pnpm dev
```

Turborepo starts the Clojure API and React frontend in parallel. You'll see interleaved output from both:

- **API** (port `3000`): prints `Server running on port 3000` once it connects to MongoDB and seeds the OAuth client record
- **Web** (port `5173`): Vite prints its startup banner and a local URL

### 5. Open the app

Navigate to [http://localhost:5173](http://localhost:5173) in your browser. You'll land on the signup page. Create an account — this triggers the full OAuth 2.0 Authorization Code + PKCE flow, ending at the todos page where you can create, complete, and delete todos.

### 6. Run the Clojure test suite (optional)

```bash
cd apps/api && clj -M:test
```

Runs 17 tests against a live MongoDB connection. Expected output: `0 failures, 0 errors`. The tests cover auth handlers, OAuth token exchange, and todo CRUD — no mocks.

## Going deeper

Each subsystem has its own doc — start with whichever interests you most. The docs assume you've already run the app locally at least once.

| Doc | What it covers |
|-----|----------------|
| [Architecture](docs/ARCHITECTURE.md) | How all the pieces connect, the full request lifecycle, and the key design decisions |
| [OAuth 2.0 + PKCE flow](docs/OAUTH_FLOW.md) | The complete Authorization Code + PKCE flow explained step by step, with real code |
| [Clojure API](apps/api/README.md) | Ring, Reitit, middleware composition, EDN config, MongoDB, and JWT — the Clojure patterns in depth |
| [React frontend](apps/web/README.md) | The OAuth client implementation, token storage strategy, TanStack Query refresh, and responsive layout |

**Suggested reading order** if you want a guided tour: Architecture → OAuth flow → Clojure API → React frontend.
