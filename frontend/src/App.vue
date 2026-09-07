<template>
  <div class="layout">
    <header class="topbar">
      <div class="topbar-inner">
        <nav class="nav">
          <RouterLink to="/fast" class="link" active-class="active">빠른시작</RouterLink>
          <RouterLink to="/browser" class="link" active-class="active">브라우저</RouterLink>
          <RouterLink to="/query" class="link" active-class="active">질의기</RouterLink>
          <RouterLink to="/realtime" class="link" active-class="active">실시간</RouterLink>
          <RouterLink to="/experiment" class="link" active-class="active">실험</RouterLink>
          <RouterLink to="/admin" class="link" active-class="active">관리</RouterLink>
        </nav>

        <!-- 우측 텍스트 로고 -->
        <div class="brand" aria-label="JeNAVI brand">
          <span class="brand-main">JeNAVI</span>
          <span class="brand-sub">AIDT Lab</span>
        </div>
      </div>
    </header>

    <main class="main">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts"></script>

<style scoped>
.layout { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; }

/* 상단 바 */
.topbar {
  position: sticky; top: 0; z-index: 10;
  background: #111; color: #fff; padding: 10px 16px;
  box-shadow: 0 2px 8px rgba(0,0,0,.15);
}

/* 좌우 정렬 래퍼 */
.topbar-inner {
  max-width: 1100px;
  margin: 0 auto;
  display: flex; align-items: center; justify-content: space-between;
  gap: 16px;
}

/* 내비 */
.nav { display: flex; gap: 12px; }
.link { color: #bbb; text-decoration: none; padding: 6px 10px; border-radius: 8px; }
.link:hover { background: rgba(255,255,255,.08); color: #fff; }
.active { background: rgba(255,255,255,.16); color: #fff; }

/* 우측 브랜드 */
.brand {
  display: flex;
  flex-direction: column;
  align-items: flex-end; /* 오른쪽 정렬 */
  line-height: 1.05;
}
.brand-main {
  font-weight: 800;
  font-size: 18px;
  letter-spacing: 0.4px;
  color: #fff;
}
.brand-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #bdbdbd;
  letter-spacing: .02em;
}

/* 반응형: 화면이 좁아지면 서브를 살짝 줄임 */
@media (max-width: 520px) {
  .brand-main { font-size: 16px; }
  .brand-sub { font-size: 11px; }
}

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

/* 전역 상단 바(테마 변수 사용) */
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
