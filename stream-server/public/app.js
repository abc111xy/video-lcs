/* 共用：主题切换 + 用户中心（无依赖） */
(function () {
  const THEME_KEY = 'spl_theme';
  const TOKEN_KEY = 'spl_token';
  const REPO = 'https://github.com/abc111xy/video-lcs';

  const SC = (window.SC = window.SC || {});

  // ---------------------------------------------------------------- 主题
  const media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function readTheme() {
    const v = localStorage.getItem(THEME_KEY);
    return v === 'light' || v === 'dark' || v === 'system' ? v : 'system';
  }

  function resolved(theme) {
    if (theme === 'system') return media && media.matches ? 'dark' : 'light';
    return theme;
  }

  function applyTheme(theme, persist) {
    const t = theme || readTheme();
    document.documentElement.setAttribute('data-theme', resolved(t));
    document.documentElement.setAttribute('data-theme-pref', t);
    if (persist) localStorage.setItem(THEME_KEY, t);
    document.dispatchEvent(new CustomEvent('sc:theme', { detail: t }));
  }

  SC.theme = {
    get: readTheme,
    set(theme) {
      applyTheme(theme, true);
      const token = localStorage.getItem(TOKEN_KEY);
      if (token) {
        fetch('/api/auth/profile', {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json', 'X-Auth-Token': token },
          body: JSON.stringify({ theme }),
        }).catch(() => {});
      }
    },
    apply: applyTheme,
  };

  applyTheme(readTheme(), false);
  if (media) {
    const onChange = () => {
      if (readTheme() === 'system') applyTheme('system', false);
    };
    media.addEventListener ? media.addEventListener('change', onChange) : media.addListener(onChange);
  }

  // ---------------------------------------------------------------- 请求
  function token() {
    return localStorage.getItem(TOKEN_KEY);
  }

  async function api(path, opts = {}) {
    const t = token();
    const res = await fetch(path, {
      method: opts.method || 'GET',
      headers: {
        'Content-Type': 'application/json',
        ...(t ? { 'X-Auth-Token': t } : {}),
      },
      body: opts.body ? JSON.stringify(opts.body) : undefined,
    });
    let data = {};
    try {
      data = await res.json();
    } catch (e) {}
    if (!res.ok) {
      const err = new Error(data.error || '请求失败');
      err.status = res.status;
      throw err;
    }
    return data;
  }

  SC.api = api;
  SC.token = token;

  // ---------------------------------------------------------------- 工具
  function el(tag, attrs, children) {
    const n = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach((k) => {
        if (k === 'class') n.className = attrs[k];
        else if (k === 'html') n.innerHTML = attrs[k];
        else if (k === 'text') n.textContent = attrs[k];
        else if (k === 'style') n.setAttribute('style', attrs[k]);
        else if (k.startsWith('on') && typeof attrs[k] === 'function') n[k] = attrs[k];
        else n.setAttribute(k, attrs[k]);
      });
    }
    (children || []).forEach((c) => c && n.appendChild(c));
    return n;
  }

  function initial(name, email) {
    const s = (name || email || '?').trim();
    return s ? s.slice(0, 1).toUpperCase() : '?';
  }

  /** 圆头像节点：有图用图，无图用首字母 */
  function avatarNode(user, cls) {
    const box = el('div', { class: 'uc-avatar ' + (cls || '') });
    if (user && user.avatar) {
      const img = el('img', { src: user.avatar, alt: '' });
      box.appendChild(img);
    } else {
      box.textContent = initial(user && user.nickname, user && user.email);
    }
    return box;
  }

  /** 压缩头像：居中裁剪为正方形，输出 200x200 JPEG */
  function compressImage(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const img = new Image();
        img.onload = () => {
          const size = Math.min(img.width, img.height);
          const sx = (img.width - size) / 2;
          const sy = (img.height - size) / 2;
          const out = 200;
          const canvas = document.createElement('canvas');
          canvas.width = out;
          canvas.height = out;
          const ctx = canvas.getContext('2d');
          ctx.drawImage(img, sx, sy, size, size, 0, 0, out, out);
          resolve(canvas.toDataURL('image/jpeg', 0.82));
        };
        img.onerror = () => reject(new Error('图片读取失败'));
        img.src = reader.result;
      };
      reader.onerror = () => reject(new Error('文件读取失败'));
      reader.readAsDataURL(file);
    });
  }

  // ---------------------------------------------------------------- 用户中心
  function openPanel(state, anchor) {
    const mask = el('div', { class: 'uc-mask', id: 'ucMask' });
    const panel = el('div', { class: 'uc-panel' });

    const msg = el('div', { class: 'uc-msg' });
    const setMsg = (text, kind) => {
      msg.textContent = text || '';
      msg.className = 'uc-msg ' + (kind || '');
    };

    // 头部
    const avatar = avatarNode(state.user, 'big');
    avatar.title = '点击更换头像';
    const fileInput = el('input', { type: 'file', accept: 'image/png,image/jpeg,image/webp', style: 'display:none' });
    avatar.onclick = () => fileInput.click();
    fileInput.onchange = async () => {
      const f = fileInput.files && fileInput.files[0];
      if (!f) return;
      if (f.size > 8 * 1024 * 1024) return setMsg('图片过大（原始文件需小于 8MB）', 'err');
      try {
        setMsg('正在上传…');
        const dataUrl = await compressImage(f);
        const r = await api('/api/auth/profile', { method: 'PATCH', body: { avatar: dataUrl } });
        state.user = r.user;
        const fresh = avatarNode(state.user, 'big');
        fresh.onclick = avatar.onclick;
        avatar.replaceWith(fresh);
        avatarEl = fresh;
        setMsg('头像已更新', 'ok');
        if (state.onChange) state.onChange(state.user);
      } catch (e) {
        setMsg(e.message, 'err');
      }
    };
    let avatarEl = avatar;

    const meta = el('div', { class: 'uc-meta' }, [
      el('div', { class: 'n', id: 'ucName', text: '' }),
      el('div', { class: 'e', id: 'ucEmail', text: state.user.email }),
    ]);
    meta.querySelector('#ucName').textContent = state.user.nickname || '未设置昵称';

    const closeBtn = el('button', { class: 'uc-close', title: '关闭', text: '×' });
    closeBtn.onclick = () => close();

    panel.appendChild(el('div', { class: 'uc-head' }, [avatarEl, meta, closeBtn]));
    panel.appendChild(fileInput);

    // 昵称
    panel.appendChild(el('div', { class: 'uc-label', text: '昵称（最多 20 字）' }));
    const nickInput = el('input', { type: 'text', maxlength: '20', placeholder: '给自己起个名字', value: state.user.nickname || '' });
    const saveNick = el('button', { class: 'uc-btn primary', text: '保存' });
    saveNick.onclick = async () => {
      try {
        const r = await api('/api/auth/profile', { method: 'PATCH', body: { nickname: nickInput.value } });
        state.user = r.user;
        meta.querySelector('#ucName').textContent = state.user.nickname || '未设置昵称';
        const fresh = avatarNode(state.user, 'big');
        fresh.title = '点击更换头像';
        fresh.onclick = () => fileInput.click();
        avatarEl.replaceWith(fresh);
        avatarEl = fresh;
        setMsg('昵称已保存', 'ok');
        if (state.onChange) state.onChange(state.user);
      } catch (e) {
        setMsg(e.message, 'err');
      }
    };
    panel.appendChild(el('div', { class: 'uc-field' }, [nickInput, saveNick]));

    // 主题
    panel.appendChild(el('div', { class: 'uc-label', text: '主题' }));
    const seg = el('div', { class: 'uc-seg' });
    const themes = [
      ['dark', '暗色'],
      ['light', '亮色'],
      ['system', '跟随系统'],
    ];
    const paint = () => {
      const cur = SC.theme.get();
      Array.from(seg.children).forEach((b) => b.classList.toggle('on', b.dataset.t === cur));
    };
    themes.forEach(([v, label]) => {
      const b = el('button', { 'data-t': v, text: label });
      b.onclick = () => {
        SC.theme.set(v);
        paint();
        setMsg('主题已切换', 'ok');
        if (state.onChange) state.onChange(state.user);
      };
      seg.appendChild(b);
    });
    paint();
    panel.appendChild(seg);

    // 链接
    const links = el('div', { class: 'uc-links' });
    links.appendChild(el('a', { href: '/terms.html', target: '_blank', text: '用户协议' }));
    links.appendChild(el('a', { href: '/privacy.html', target: '_blank', text: '隐私政策' }));
    links.appendChild(el('a', { href: REPO, target: '_blank', rel: 'noopener', html: '开源仓库<span class="muted">AGPL-3.0</span>' }));

    const logout = el('button', { class: 'link', text: '退出登录' });
    logout.onclick = async () => {
      try {
        await api('/api/auth/logout', { method: 'POST' });
      } catch (e) {}
      localStorage.removeItem(TOKEN_KEY);
      location.href = '/login.html';
    };
    links.appendChild(logout);

    const del = el('button', { class: 'link danger', text: '注销账号' });
    links.appendChild(del);
    panel.appendChild(links);

    // 注销确认区（默认隐藏）
    const zone = el('div', { class: 'uc-danger-zone', style: 'display:none' });
    zone.appendChild(el('div', {
      class: 'uc-label',
      html: '注销将<strong>永久删除</strong>账号、昵称、头像与全部数据，不可恢复。请输入登录密码确认：',
    }));
    const pw = el('input', { type: 'password', placeholder: '登录密码' });
    const doDel = el('button', { class: 'uc-btn danger', text: '确认注销' });
    doDel.onclick = async () => {
      if (!pw.value) return setMsg('请输入密码', 'err');
      if (!confirm('确认注销账号？此操作不可恢复。')) return;
      try {
        await api('/api/auth/delete-account', { method: 'POST', body: { password: pw.value } });
        localStorage.removeItem(TOKEN_KEY);
        alert('账号已注销');
        location.href = '/login.html';
      } catch (e) {
        setMsg(e.message, 'err');
      }
    };
    zone.appendChild(el('div', { class: 'uc-field', style: 'margin-top:8px' }, [pw, doDel]));
    panel.appendChild(zone);
    del.onclick = () => {
      zone.style.display = zone.style.display === 'none' ? 'block' : 'none';
      if (zone.style.display === 'block') pw.focus();
    };

    panel.appendChild(msg);

    function close() {
      mask.remove();
      document.removeEventListener('keydown', onKey);
    }
    function onKey(e) {
      if (e.key === 'Escape') close();
    }
    document.addEventListener('keydown', onKey);
    mask.onclick = (e) => {
      if (e.target === mask) close();
    };
    mask.appendChild(panel);
    document.body.appendChild(mask);
    setTimeout(() => nickInput.focus(), 30);
  }

  // ---------------------------------------------------------------- 挂载
  SC.mountUserCenter = function (container, opts) {
    if (!container) return;
    const state = { user: null, onChange: opts && opts.onChange };
    container.innerHTML = '';

    const t = token();
    if (!t) {
      container.appendChild(el('a', { class: 'uc-login-link', href: '/login.html', text: '登录 / 注册' }));
      return;
    }

    api('/api/auth/me')
      .then((d) => {
        state.user = d.user;
        // 仅当本机从未选择过主题时，才采用服务端记录（换设备首次登录生效），
        // 否则会把用户刚切的亮/暗主题又覆盖回去
        if (state.user && state.user.theme && !localStorage.getItem('spl_theme')) {
          SC.theme.apply(state.user.theme, true);
        }
        render();
      })
      .catch((e) => {
        if (e.status === 401) {
          localStorage.removeItem(TOKEN_KEY);
          container.innerHTML = '';
          container.appendChild(el('a', { class: 'uc-login-link', href: '/login.html', text: '登录 / 注册' }));
        } else {
          container.appendChild(el('a', { class: 'uc-login-link', href: '/login.html', text: '登录 / 注册' }));
        }
      });

    function render() {
      container.innerHTML = '';
      const entry = el('button', { class: 'uc-entry', title: '用户中心' });
      entry.appendChild(avatarNode(state.user));
      entry.appendChild(el('span', { class: 'uc-name', text: state.user.nickname || state.user.email.split('@')[0] }));
      entry.onclick = () => openPanel(state);
      container.appendChild(entry);
    }
  };

  SC.initTheme = function () {};
})();
