#!/usr/bin/env bash
# Seeds fake "remote" repos for the docs screenshots: klant-x/backend (Laravel-ish, base develop),
# klant-x/frontend (Vue, base main), klant-y/shop (single repo).
#   tools/sandbox/seed-repos.sh <demo-root>     → <demo-root>/remotes/*.git
set -euo pipefail
ROOT="$1"
REMOTES="$ROOT/remotes"
SEED="$ROOT/seed"
rm -rf "$REMOTES" "$SEED"
mkdir -p "$REMOTES" "$SEED"

commit() { git -C "$1" add -A && git -C "$1" -c user.name="Sanne de Vries" -c user.email=sanne@example.com commit -qm "$2"; }
bare() { git clone -q --bare "$SEED/$1" "$REMOTES/$1.git"; }

# ---- klant-x backend
B="$SEED/klant-x-backend"
git init -q -b develop "$B"
mkdir -p "$B/app/Http/Controllers/Auth" "$B/app/Models" "$B/app/Services" "$B/routes" "$B/config" "$B/database/migrations" "$B/tests/Feature"
cat > "$B/.gitignore" <<'EOF'
/vendor/
/node_modules/
.env
.phpunit.result.cache
EOF
cat > "$B/composer.json" <<'EOF'
{
    "name": "klant-x/backend",
    "type": "project",
    "require": {
        "php": "^8.3",
        "laravel/framework": "^12.0",
        "laravel/sanctum": "^4.0"
    },
    "require-dev": {
        "phpunit/phpunit": "^11.0"
    },
    "autoload": {
        "psr-4": { "App\\": "app/" }
    }
}
EOF
cat > "$B/.env.example" <<'EOF'
APP_NAME="Klant X"
APP_ENV=local
APP_URL=http://klant-x-backend.test
DB_DATABASE=klant_x
EOF
cat > "$B/README.md" <<'EOF'
# Klant X backend

```bash
composer install
php artisan migrate
```
EOF
cat > "$B/routes/api.php" <<'EOF'
<?php

use App\Http\Controllers\Auth\LoginController;
use App\Http\Controllers\OrderController;
use Illuminate\Support\Facades\Route;

Route::post('/login', [LoginController::class, 'store']);
Route::post('/logout', [LoginController::class, 'destroy'])->middleware('auth:sanctum');

Route::middleware('auth:sanctum')->group(function () {
    Route::get('/orders', [OrderController::class, 'index']);
    Route::get('/orders/{order}', [OrderController::class, 'show']);
});
EOF
cat > "$B/app/Models/User.php" <<'EOF'
<?php

namespace App\Models;

use Illuminate\Foundation\Auth\User as Authenticatable;
use Laravel\Sanctum\HasApiTokens;

class User extends Authenticatable
{
    use HasApiTokens;

    protected $fillable = ['name', 'email', 'password'];

    protected $hidden = ['password', 'remember_token'];
}
EOF
cat > "$B/app/Http/Controllers/Auth/LoginController.php" <<'EOF'
<?php

namespace App\Http\Controllers\Auth;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;
use Illuminate\Validation\ValidationException;

class LoginController extends Controller
{
    public function store(Request $request): JsonResponse
    {
        $credentials = $request->validate([
            'email' => ['required', 'email'],
            'password' => ['required'],
        ]);

        if (! Auth::attempt($credentials)) {
            throw ValidationException::withMessages([
                'email' => __('auth.failed'),
            ]);
        }

        $token = $request->user()->createToken('spa')->plainTextToken;

        return response()->json(['token' => $token]);
    }

    public function destroy(Request $request): JsonResponse
    {
        $request->user()->currentAccessToken()->delete();

        return response()->json(status: 204);
    }
}
EOF
cat > "$B/app/Http/Controllers/Controller.php" <<'EOF'
<?php

namespace App\Http\Controllers;

abstract class Controller
{
}
EOF
cat > "$B/app/Http/Controllers/OrderController.php" <<'EOF'
<?php

namespace App\Http\Controllers;

use App\Models\Order;
use Illuminate\Http\Request;

class OrderController extends Controller
{
    public function index(Request $request)
    {
        return $request->user()->orders()->latest()->paginate(20);
    }

    public function show(Order $order)
    {
        $this->authorize('view', $order);

        return $order->load('lines');
    }
}
EOF
cat > "$B/config/auth.php" <<'EOF'
<?php

return [
    'defaults' => ['guard' => 'web', 'passwords' => 'users'],
    'guards' => ['web' => ['driver' => 'session', 'provider' => 'users']],
    'providers' => ['users' => ['driver' => 'eloquent', 'model' => App\Models\User::class]],
];
EOF
cat > "$B/tests/Feature/LoginTest.php" <<'EOF'
<?php

namespace Tests\Feature;

use App\Models\User;
use Tests\TestCase;

class LoginTest extends TestCase
{
    public function test_a_user_can_log_in(): void
    {
        $user = User::factory()->create();

        $this->postJson('/api/login', ['email' => $user->email, 'password' => 'password'])
            ->assertOk()
            ->assertJsonStructure(['token']);
    }
}
EOF
commit "$B" "Initial Laravel API"
cat > "$B/database/migrations/2026_09_01_000000_create_orders_table.php" <<'EOF'
<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('orders', function (Blueprint $table) {
            $table->id();
            $table->foreignId('user_id')->constrained();
            $table->string('status')->default('open');
            $table->timestamps();
        });
    }
};
EOF
commit "$B" "Orders table"
git -C "$B" branch main
bare klant-x-backend

# ---- klant-x frontend
F="$SEED/klant-x-frontend"
git init -q -b main "$F"
mkdir -p "$F/src/components" "$F/src/api" "$F/src/views"
cat > "$F/.gitignore" <<'EOF'
node_modules/
dist/
.env
EOF
cat > "$F/package.json" <<'EOF'
{
  "name": "klant-x-frontend",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc && vite build"
  },
  "dependencies": {
    "axios": "^1.7.0",
    "vue": "^3.5.0",
    "vue-router": "^4.4.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.1.0",
    "typescript": "^5.6.0",
    "vite": "^6.0.0",
    "vue-tsc": "^2.1.0"
  }
}
EOF
cat > "$F/README.md" <<'EOF'
# Klant X frontend

```bash
npm install
npm run dev
```
EOF
cat > "$F/.env.example" <<'EOF'
VITE_API_URL=http://klant-x-backend.test/api
EOF
cat > "$F/src/api/client.ts" <<'EOF'
import axios from 'axios'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL,
  headers: { Accept: 'application/json' },
})

export function setToken(token: string | null) {
  if (token) api.defaults.headers.common.Authorization = `Bearer ${token}`
  else delete api.defaults.headers.common.Authorization
}
EOF
cat > "$F/src/components/LoginForm.vue" <<'EOF'
<script setup lang="ts">
import { ref } from 'vue'
import { api, setToken } from '../api/client'

const email = ref('')
const password = ref('')
const error = ref<string | null>(null)

async function submit() {
  error.value = null
  try {
    const { data } = await api.post('/login', { email: email.value, password: password.value })
    setToken(data.token)
  } catch {
    error.value = 'These credentials do not match our records.'
  }
}
</script>

<template>
  <form class="login" @submit.prevent="submit">
    <input v-model="email" type="email" placeholder="Email" required>
    <input v-model="password" type="password" placeholder="Password" required>
    <p v-if="error" class="error">{{ error }}</p>
    <button type="submit">Log in</button>
  </form>
</template>
EOF
cat > "$F/src/views/OrdersView.vue" <<'EOF'
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'

const orders = ref<{ id: number; status: string }[]>([])

onMounted(async () => {
  orders.value = (await api.get('/orders')).data.data
})
</script>

<template>
  <ul>
    <li v-for="order in orders" :key="order.id">#{{ order.id }} · {{ order.status }}</li>
  </ul>
</template>
EOF
commit "$F" "Initial Vue app"
bare klant-x-frontend

# ---- klant-y shop (single repo)
S="$SEED/klant-y-shop"
git init -q -b main "$S"
mkdir -p "$S/src/Controller"
cat > "$S/.gitignore" <<'EOF'
/vendor/
.env
EOF
cat > "$S/composer.json" <<'EOF'
{
    "name": "klant-y/shop",
    "require": { "php": "^8.3", "symfony/framework-bundle": "^7.1" }
}
EOF
cat > "$S/src/Controller/ProductController.php" <<'EOF'
<?php

namespace App\Controller;

use Symfony\Bundle\FrameworkBundle\Controller\AbstractController;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\Routing\Attribute\Route;

class ProductController extends AbstractController
{
    #[Route('/api/products', methods: ['GET'])]
    public function index(): JsonResponse
    {
        return $this->json([]);
    }
}
EOF
commit "$S" "Initial Symfony shop"
bare klant-y-shop

echo "seeded $REMOTES"

# ---- vendor stubs for klant-x backend (not committed: copied into main/backend/vendor by DemoWorkspaces), so the
# editor resolves the Laravel classes instead of underlining them
STUBS="$ROOT/stubs/vendor"
mkdir -p "$STUBS/laravel/framework" "$STUBS/laravel/sanctum"
cat > "$STUBS/autoload.php" <<'PHP'
<?php

return require __DIR__ . '/composer/autoload_real.php';
PHP
cat > "$STUBS/laravel/framework/stubs.php" <<'PHP'
<?php

namespace Illuminate\Http {
    class Request
    {
        public function validate(array $rules): array { return []; }
        public function boolean(string $key): bool { return false; }
        /** @return \App\Models\User */
        public function user() {}
        public function session(): \Illuminate\Session\Store {}
    }
    class JsonResponse {}
}

namespace Illuminate\Session {
    class Store
    {
        public function regenerate(): bool { return true; }
    }
}

namespace Illuminate\Support\Facades {
    class Auth
    {
        public static function attempt(array $credentials = [], bool $remember = false): bool { return false; }
    }
    class Route
    {
        public static function post(string $uri, array $action): \Illuminate\Routing\Route {}
        public static function get(string $uri, array $action): \Illuminate\Routing\Route {}
        public static function middleware(string $middleware): \Illuminate\Routing\RouteRegistrar {}
    }
}

namespace Illuminate\Routing {
    class Route
    {
        public function middleware(string $middleware): static { return $this; }
    }
    class RouteRegistrar
    {
        public function group(\Closure $routes): void {}
    }
    class ResponseFactory
    {
        public function json(array $data = [], int $status = 200): \Illuminate\Http\JsonResponse {}
    }
}

namespace Illuminate\Validation {
    class ValidationException extends \RuntimeException
    {
        public static function withMessages(array $messages): static {}
    }
}

namespace Illuminate\Foundation\Auth {
    class User {}
}

namespace {
    function response(): \Illuminate\Routing\ResponseFactory {}
    function __(string $key): string { return $key; }
}
PHP
cat > "$STUBS/laravel/sanctum/stubs.php" <<'PHP'
<?php

namespace Laravel\Sanctum {
    trait HasApiTokens
    {
        public function createToken(string $name): NewAccessToken {}
        public function currentAccessToken(): PersonalAccessToken {}
    }
    class NewAccessToken
    {
        public string $plainTextToken;
    }
    class PersonalAccessToken
    {
        public function delete(): bool { return true; }
    }
}
PHP
