<template>
  <div class="layout">
    <header class="topbar">
      <nav class="nav">
        <RouterLink to="/browser" class="link" active-class="active">브라우저</RouterLink>
        <RouterLink to="/query" class="link" active-class="active">질의기</RouterLink>
        <RouterLink to="/admin" class="link" active-class="active">관리</RouterLink>
      </nav>
    </header>

    <main class="main">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts"></script>

<style scoped>
.layout { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; }

.topbar {
  position: sticky; top: 0; z-index: 10;
  background: #111; color: #fff; padding: 10px 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,.15);
}
.nav { display: flex; gap: 12px; }
.link { color: #bbb; text-decoration: none; padding: 6px 10px; border-radius: 8px; }
.link:hover { background: rgba(255,255,255,.08); color: #fff; }
.active { background: rgba(255,255,255,.16); color: #fff; }

.main { max-width: 1100px; margin: 24px auto; padding: 0 16px; }
</style>


<style>
:root{
  --bg: #0b0b0b;
  --topbar: #111111;
  --surface: #171717;
  --surface-2: #1f1f1f;
  --border: #2a2a2a;
  --text: #eaeaea;
  --muted: #bdbdbd;
  --accent: #7aa2ff;
  --accent-2: #6fd8b4;
}
html, body { background: var(--bg); color: var(--text); }

.layout { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; }

.topbar {
  position: sticky; top: 0; z-index: 10;
  background: var(--topbar); color: var(--text); padding: 10px 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,.35);
}
.nav { display: flex; gap: 12px; }
.link { color: var(--muted); text-decoration: none; padding: 6px 10px; border-radius: 8px; }
.link:hover { background: rgba(255,255,255,.08); color: var(--text); }
.active { background: rgba(255,255,255,.16); color: var(--text); }

.main { max-width: 1100px; margin: 24px auto; padding: 0 16px; }
</style>