(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.HengduOnlinePageBridge = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const CHUNK = 64 * 1024, MAX_PAGE = 32 * 1024 * 1024, DEADLINE = 10 * 60 * 1000;

  function read(blob) {
    if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer();
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(r.result);
      r.onerror = () => reject(r.error || new Error('Image read failed'));
      r.readAsArrayBuffer(blob);
    });
  }

  function timed(promise, millis) {
    return new Promise((resolve, reject) => {
      let timer = setTimeout(() => reject(Error('Timed out')), millis);
      Promise.resolve(promise).then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
    });
  }

  async function run(url, token, receiver, resolveChapter, fetchImage) {
    const deadline = Date.now() + DEADLINE;
    let heartbeat = setInterval(() => {
      try { if (Date.now() > deadline || !receiver.ping(token)) clearInterval(heartbeat); }
      catch (e) { clearInterval(heartbeat); }
    }, 10000);
    try {
      const data = await timed(resolveChapter(url), 120000);
      if (!receiver.ping(token)) return;
      if (!data || !Array.isArray(data.images) || !data.images.length || data.images.length > 1000) throw Error('Invalid chapter');
      if (!receiver.beginOnline(token, JSON.stringify({title: data.title || '', nextChapter: data.nextChapter || null, pageCount: data.images.length}))) return;
      for (let page = 0; page < data.images.length; page++) {
        if (Date.now() > deadline || !receiver.ping(token)) throw Error('Timed out');
        const response = await timed(fetchImage(data.images[page]), 45000);
        if (!response.ok || !(response.headers.get('content-type') || '').toLowerCase().startsWith('image/')) throw Error('Image unavailable');
        const declaredSize = Number(response.headers.get('content-length') || 0);
        if (declaredSize > MAX_PAGE) throw Error('Image too large');
        const blob = await timed(response.blob(), 45000);
        if (!blob.size || blob.size > MAX_PAGE) throw Error('Invalid image size');
        if (!receiver.beginOnlinePage(token, page, blob.size, blob.type)) return;
        for (let offset = 0; offset < blob.size;) {
          const bytes = new Uint8Array(await read(blob.slice(offset, offset + CHUNK)));
          let binary = '';
          for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
          let done = offset + bytes.length === blob.size;
          if (!receiver.append(token, page, offset, btoa(binary), done)) return;
          offset += bytes.length;
        }
      }
      receiver.complete(token);
    } catch (e) {
      receiver.error(token, String(e && e.message || e));
    } finally {
      clearInterval(heartbeat);
    }
  }
  return Object.freeze({run});
});
