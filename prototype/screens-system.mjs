/**
 * Figma system-concept screen renderers.
 *
 * These are visual, local-only simulations. They never invoke browser,
 * Android, Xiaomi, notification, alarm, or location APIs.
 */

const escapeHTML = (value) => String(value ?? '').replace(/[&<>'"]/g, (character) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;',
}[character]));

/**
 * @param {object} deps
 * @param {(file: string, alt: string, className?: string) => string} deps.asset
 * @param {object|(() => object)} deps.state Current { config, runtime } state.
 * @returns {Record<'lock' | 'island' | 'island-expand' | 'ringing', () => string>}
 */
export function createSystemScreens(deps) {
  const { asset, state } = deps;
  if (typeof asset !== 'function') {
    throw new TypeError('createSystemScreens requires an asset helper.');
  }

  const read = () => {
    const snapshot = (typeof state === 'function' ? state() : state) || {};
    return { config: snapshot.config || snapshot, runtime: snapshot.runtime || {} };
  };
  const art = (file, alt, className = '') => asset(file, alt, `system-asset ${className}`);
  const action = (label, name, className = '') =>
    `<button type="button" class="system-action ${className}" data-action="${name}">${escapeHTML(label)}</button>`;

  const status = (time, light, dot, signal) => `
    <div class="system-status ${light ? 'is-light' : ''}" aria-label="${time}">
      <span class="system-status-time">${time}</span>
      ${art(dot, '', 'system-status-dot')}
      ${art(signal, '', 'system-status-signal')}
    </div>`;

  const gesture = (light) => `
    <div class="system-gesture ${light ? 'is-light' : ''}" aria-hidden="true"><i></i></div>`;

  const launcher = () => {
    const apps = [
      ['日历', 'dbd05c15-00f6-44a3-a62b-c8dc69829268.svg'],
      ['时钟', '57fe61cd-c0fe-422f-9c43-a8deb83981a9.svg'],
      ['地图', '9c43d8a1-fab4-4379-bf1f-4422cf7009a3.svg'],
      ['设置', '9c01a399-f42c-4795-ac58-b419022ee6b9.svg'],
      ['天气', '3a054292-7916-4e62-93b5-1a583a428df9.svg'],
      ['知途', '2240b18e-8e5e-48a9-9678-74ce185e3301.svg', true],
      ['文件', '80bee3f1-1bd3-4006-a094-680532303cb8.svg'],
      ['音乐', '07f8f950-c39a-4104-a64e-5a4365089daa.svg'],
    ];
    const dock = [
      ['235ce1d2-63eb-414b-a6f6-d6deb460f5c6.svg', '主页'],
      ['318e2552-9014-417e-9d97-9bb823e9799c.svg', '搜索'],
      ['ad6573a7-d7f1-4289-b85d-b25c41cd856b.svg', '通知'],
      ['f19fced9-99d2-4418-ae56-f791be8babe7.svg', '锁定'],
    ];
    return `
      <div class="system-launcher">
        <div class="system-launcher-time">
          <time>07:58</time><strong>8月27日&nbsp; 星期四</strong><span>今天，也要从容出发。</span>
        </div>
        <section class="system-weather-widget" aria-label="杭州天气">
          ${art('1cfb6d23-2b73-45d2-92a3-eb0205bc7459.svg', '小雨', 'system-widget-rain')}
          <div><strong>杭州 · 小雨</strong><b>22°</b></div>
          <small>21° / 26°　·　出门记得带伞</small>
        </section>
        <div class="system-app-grid">
          ${apps.map(([name, icon, active]) => `
            <div class="system-app">
              <span class="${active ? 'is-active' : ''}">${art(icon, '', 'system-app-icon')}</span>
              <small>${name}</small>
            </div>`).join('')}
        </div>
        <div class="system-dock">
          ${dock.map(([icon, label]) => `<span aria-label="${label}">${art(icon, '', 'system-dock-icon')}</span>`).join('')}
        </div>
        <p>系统桌面示意 · 超级岛为概念适配</p>
      </div>`;
  };

  return {
    lock() {
      return `<section class="system-screen system-lock" aria-label="锁屏出发提醒模拟">
        ${art('a437caa4-700c-4e5f-bc12-2307c4951d22.svg', '锁屏壁纸', 'system-wallpaper')}
        ${status('07:58', true, '28c3b4b8-6c2c-40de-9a82-a9da843168d8.svg', 'e1dbe3c0-1a3c-4947-bc22-ae0185653aaa.svg')}
        <div class="system-lock-body">
          ${art('9e4049e9-afe4-4629-9741-29a95eca4f16.svg', '锁定', 'system-lock-icon')}
          <div class="system-lock-clock"><time>07:58</time><strong>8月27日&nbsp; 星期四</strong><span>${art('3415c66d-becb-4315-89ff-8eca8ce34e3d.svg', '', 'system-weather-icon')}22°&nbsp; 小雨</span></div>
          <article class="system-lock-notification">
            <header><span>${art('58b0104b-5dc5-410a-802b-27638e3cdc04.svg', '', 'system-notification-icon')}</span><b>知途 · 通勤提醒</b><small>现在</small></header>
            <h2>5 分钟后，该出发了</h2>
            <p>08:03 出发 · 08:50 预计到达<br>路上有小雨，记得带伞。</p>
            <div>${action('稍后提醒', 'system-lock-later')}${action('查看通勤', 'system-view-route', 'is-strong')}</div>
          </article>
          <div class="system-unlock">${art('06dd0f9a-866b-4885-bc25-aaea40c7c46d.svg', '解锁', 'system-unlock-icon')}<p>上滑解锁，查看详细路线<small>锁屏通知效果示意 · 不显示具体地址</small></p></div>
        </div>
        ${gesture(true)}
      </section>`;
    },

    island() {
      return `<section class="system-screen system-island" aria-label="超级岛摘要模拟">
        ${art('5ca175b4-54a0-46cd-89d6-9a2c5afc0ddb.svg', '系统桌面背景', 'system-wallpaper')}
        ${status('07:58', false, '08f4844c-c755-4188-923d-a28c8913775f.svg', '72541e4d-230f-4516-99e7-d6937d09b1cb.svg')}
        ${launcher()}
        <button type="button" class="system-island-pill" data-action="system-expand-island" aria-label="展开通勤提醒">
          ${art('ab6dc7e7-5a10-4fe0-8a30-3ed09a40a78d.svg', '', 'system-pill-car')}
          <span>08:03 出发</span><i></i><b>5 分钟</b>${art('2c8472e9-5e3b-4fc1-82b2-e351fe8adc1d.svg', '', 'system-pill-down')}
        </button>
        ${gesture(false)}
      </section>`;
    },

    'island-expand'() {
      return `<section class="system-screen system-island system-island-expanded" aria-label="超级岛详情模拟">
        ${art('fafd73fb-8cdd-4185-a8eb-a9a60865c115.svg', '系统桌面背景', 'system-wallpaper')}
        ${status('07:58', false, 'a7b43933-56c6-4b94-a76a-aec5fb58c34a.svg', '374fd80e-ea99-4b50-a1b2-9e9c86e8f9ad.svg')}
        ${launcher()}
        <div class="system-island-shade" aria-hidden="true"></div>
        <article class="system-island-card">
          <header>${art('b392ec4b-aea5-4405-b9bb-2a52e22714d5.svg', '', 'system-card-route')}<b>知途 · 通勤提醒</b><button type="button" data-action="system-collapse-island" aria-label="收起提醒">${art('b60ce5db-b5e7-4df1-9f66-74c461be4413.svg', '', 'system-card-down')}</button></header>
          <div class="system-island-count"><strong>5</strong><b>分钟后出发</b><time>08:03</time></div>
          <p>路上有小雨，记得带伞。<br>通勤约 47 分钟 · 预计 08:50 到达</p>
          <div class="system-progress">${art('ba979514-c875-4285-b7d5-8bc65437ba98.svg', '', 'system-progress-icon')}<i><b></b></i>${art('b7279e55-573c-4d2c-9b96-90853fc384e7.svg', '', 'system-progress-icon')}</div>
          <div class="system-island-actions">${action('稍后提醒', 'system-island-later')}${action('查看路线', 'system-view-route', 'is-bright')}</div>
        </article>
        ${gesture(false)}
      </section>`;
    },

    ringing() {
      const { config, runtime } = read();
      const baseline = escapeHTML(config.recurringPlan?.defaultWake || '07:30');
      const snoozed = runtime.snoozed ? '<p class="system-simulation-state">已模拟贪睡，本原型未调用系统闹钟。</p>' : '';
      const snoozeMinutes = config.preferences?.snoozeMinutes ?? 10;
      return `<section class="system-screen system-ringing" aria-label="提前闹钟响铃模拟">
        ${art('90efd783-558d-459e-9023-617c1323b182.svg', '提前闹钟壁纸', 'system-wallpaper')}
        ${status('07:18', true, 'c7b6f881-64e0-4bf1-b90a-7678a3cb38f9.svg', '24b9be6c-eb1d-4f38-9995-4f633c4823bc.svg')}
        <div class="system-ringing-body">
          <header>${art('bda1ff1c-5875-4d86-89d5-d5e2340fdb6a.svg', '', 'system-ringing-clock')}<b>知途 · 提前闹钟</b></header>
          <div class="system-ringing-time"><span>8月27日&nbsp; 星期四</span><time>07:18</time><b>今天，比平时早 12 分钟</b></div>
          <article class="system-ringing-reason"><h2>${art('59798ef3-4b7c-419a-b01f-742c7587a2ba.svg', '', 'system-ringing-rain')}今天路上有小雨</h2><p>通勤约 47 分钟，建议 08:03 出发。<br>慢慢准备，也能准时到达。</p></article>
          <div class="system-ringing-actions">${action('停止', 'system-stop-alarm', 'is-stop')}${action(`贪睡 ${snoozeMinutes} 分钟`, 'system-snooze-alarm', 'is-snooze')}${snoozed}</div>
          <p class="system-ringing-foot">仅停止本次提前闹钟<br>系统时钟的 ${baseline} 闹钟仍保持原设置</p>
        </div>
        ${gesture(true)}
      </section>`;
    },
  };
}
