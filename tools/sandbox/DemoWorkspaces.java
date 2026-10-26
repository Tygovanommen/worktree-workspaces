import nl.netonic.workspaces.core.GitRunner;
import nl.netonic.workspaces.core.IdeaFiles;
import nl.netonic.workspaces.core.Ticket;
import nl.netonic.workspaces.core.Workspace;
import nl.netonic.workspaces.core.WorkspaceOps;
import nl.netonic.workspaces.core.WorkspaceOps.RepoChoice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the demo workspaces for the docs screenshots with the plugin's own core, from the remotes made by
 * seed-repos.sh. Run through tools/sandbox/demo.sh.
 *
 *   klant-x   backend + frontend; tickets LOGIN-42 (both, backend dirty + 1 unpushed), PAY-7 (backend only),
 *             SHOP-3 (both, clean, tab closed later)
 *   klant-y   single repo; ticket API-12
 */
public class DemoWorkspaces {
  static final GitRunner GIT = GitRunner.fromSystem();

  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]).toAbsolutePath();
    Path remotes = root.resolve("remotes");
    Path projects = root.resolve("PhpstormProjects");
    Files.createDirectories(projects);
    WorkspaceOps ops = new WorkspaceOps(GIT);

    Workspace x = ops.createWorkspace(projects, "klant-x");
    ops.addRepoByClone(x, "backend", remotes.resolve("klant-x-backend.git").toString(), WorkspaceOps.Log.NONE);
    ops.addRepoByClone(x, "frontend", remotes.resolve("klant-x-frontend.git").toString(), WorkspaceOps.Log.NONE);
    x = Workspace.load(x.getRoot());
    Path main = x.mainDir();
    Files.copy(main.resolve("backend/.env.example"), main.resolve("backend/.env"));
    Files.copy(main.resolve("frontend/.env.example"), main.resolve("frontend/.env"));
    // what composer install / npm ci leave behind, copied into every ticket
    copyTree(root.resolve("stubs/vendor"), main.resolve("backend/vendor"));
    Files.createDirectories(main.resolve("frontend/node_modules/vue"));
    Files.writeString(main.resolve("frontend/node_modules/vue/package.json"), "{\"name\": \"vue\", \"version\": \"3.5.0\"}\n");
    IdeaFiles.prepare(x.ticket(Workspace.MAIN));

    Ticket login = ops.createTicket(x, "LOGIN-42", x.getConfig().branchFor("LOGIN-42"), List.of(
        new RepoChoice("backend", true, null), new RepoChoice("frontend", true, null)), WorkspaceOps.Log.NONE);
    Path be = x.repoDir(login.getName(), "backend");
    Path fe = x.repoDir(login.getName(), "frontend");
    // one pushed commit on the frontend, one unpushed on the backend, then three changed files
    edit(fe.resolve("src/components/LoginForm.vue"), "const error = ref<string | null>(null)\n",
        "const error = ref<string | null>(null)\nconst remember = ref(false)\n");
    commit(fe, "LOGIN-42 Remember me checkbox");
    git(fe, "push", "-q", "-u", "origin", "feature/LOGIN-42");
    edit(be.resolve("app/Http/Controllers/Auth/LoginController.php"), "if (! Auth::attempt($credentials)) {",
        "if (! Auth::attempt($credentials, $request->boolean('remember'))) {");
    commit(be, "LOGIN-42 Remember me on login");
    git(be, "push", "-q", "-u", "origin", "feature/LOGIN-42");
    edit(be.resolve("app/Http/Controllers/Auth/LoginController.php"), "        'password' => ['required'],\n",
        "        'password' => ['required'],\n            'remember' => ['boolean'],\n");
    commit(be, "LOGIN-42 Validate remember flag");
    edit(be.resolve("routes/api.php"), "Route::post('/login', [LoginController::class, 'store']);",
        "Route::post('/login', [LoginController::class, 'store'])->middleware('throttle:login');");
    edit(be.resolve("tests/Feature/LoginTest.php"), "    }\n}\n", """
            }

            public function test_login_is_throttled(): void
            {
                for ($i = 0; $i < 6; $i++) {
                    $this->postJson('/api/login', ['email' => 'x@example.com', 'password' => 'wrong']);
                }

                $this->postJson('/api/login', ['email' => 'x@example.com', 'password' => 'wrong'])
                    ->assertStatus(429);
            }
        }
        """);
    edit(be.resolve("app/Http/Controllers/Auth/LoginController.php"), "        $token = ",
        "        $request->session()->regenerate();\n\n        $token = ");

    Ticket pay = ops.createTicket(x, "PAY-7", x.getConfig().branchFor("PAY-7"), List.of(
        new RepoChoice("backend", true, null), new RepoChoice("frontend", false, null)), WorkspaceOps.Log.NONE);
    Path payBe = x.repoDir(pay.getName(), "backend");
    Files.createDirectories(payBe.resolve("app/Services"));
    Files.writeString(payBe.resolve("app/Services/PaymentService.php"), """
        <?php

        namespace App\\Services;

        use App\\Models\\Order;

        class PaymentService
        {
            public function checkoutUrl(Order $order): string
            {
                return route('payments.redirect', $order);
            }
        }
        """);
    commit(payBe, "PAY-7 Payment service");
    git(payBe, "push", "-q", "-u", "origin", "feature/PAY-7");

    ops.createTicket(x, "SHOP-3", x.getConfig().branchFor("SHOP-3"), List.of(
        new RepoChoice("backend", true, null), new RepoChoice("frontend", true, null)), WorkspaceOps.Log.NONE);

    Workspace y = ops.createWorkspace(projects, "klant-y");
    ops.addRepoByClone(y, null, remotes.resolve("klant-y-shop.git").toString(), WorkspaceOps.Log.NONE);
    y = Workspace.load(y.getRoot());
    Files.createDirectories(y.mainDir().resolve("vendor"));
    Files.writeString(y.mainDir().resolve("vendor/autoload.php"), "<?php\n");
    IdeaFiles.prepare(y.ticket(Workspace.MAIN));
    ops.createTicket(y, "API-12", y.getConfig().branchFor("API-12"), List.of(
        new RepoChoice(y.singleRepoName(), true, null)), WorkspaceOps.Log.NONE);

    System.out.println("demo workspaces in " + projects);
  }

  static void copyTree(Path from, Path to) throws Exception {
    try (var paths = Files.walk(from)) {
      for (Path p : (Iterable<Path>) paths::iterator) {
        Path t = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) Files.createDirectories(t);
        else Files.copy(p, t);
      }
    }
  }

  static void edit(Path file, String from, String to) throws Exception {
    String s = Files.readString(file);
    if (!s.contains(from)) throw new IllegalStateException(file + " has no " + from);
    Files.writeString(file, s.replace(from, to));
  }

  static void commit(Path dir, String message) throws Exception {
    git(dir, "add", "-A");
    git(dir, "-c", "user.name=Sanne de Vries", "-c", "user.email=sanne@example.com", "commit", "-qm", message);
  }

  static void git(Path dir, String... args) throws Exception {
    GIT.run(dir, args).orThrow("git " + String.join(" ", args));
  }
}
