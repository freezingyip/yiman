/*
 * Prototype adapter for the existing hengdu-offline IndexedDB.
 * This is not loaded by the app and does not integrate Mihon by itself.
 * Call from the trusted reader.local page only; do not expose it to remote sites.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.HengduOfflinePageBridge = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const MAX_CHUNK_BYTES = 64 * 1024;

  function readBlob(blob) {
    // Blob.arrayBuffer() is absent in older Android WebViews.
    if (typeof blob.arrayBuffer === 'function') return blob.arrayBuffer();
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(reader.error || new Error('Unable to read cached page'));
      reader.readAsArrayBuffer(blob);
    });
  }

  function getRecord(db, url) {
    return new Promise((resolve, reject) => {
      const tx = db.transaction('chapters', 'readonly');
      const request = tx.objectStore('chapters').get(url);
      request.onsuccess = () => resolve(request.result || null);
      request.onerror = () => reject(request.error || new Error('Unable to read offline chapter'));
      tx.onabort = () => reject(tx.error || new Error('Offline read aborted'));
    });
  }

  // dbPromise may be the app's existing dbReady; do not create or upgrade a database here.
  function create(dbPromise) {
    const snapshots = new Map();
    async function record(url) {
      if (typeof url !== 'string' || !url) throw new TypeError('A chapter URL is required');
      if (!snapshots.has(url)) snapshots.set(url, getRecord(await dbPromise, url));
      const item = await snapshots.get(url);
      if (!item || !Array.isArray(item.blobs)) throw new Error('Offline chapter not found');
      return item;
    }
    async function descriptor(url) {
      const item = await record(url);
      return {
        url: item.url,
        title: item.title || '',
        nextChapter: item.nextChapter || null,
        pages: item.blobs.map(blob => ({size: blob.size, type: blob.type || 'application/octet-stream'})),
      };
    }
    // At most 64 KiB per call, so the JavaScript interface never transfers a whole page at once.
    async function readChunk(url, page, offset, count) {
      if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(offset) || offset < 0 ||
          !Number.isSafeInteger(count) || count < 1 || count > MAX_CHUNK_BYTES) {
        throw new RangeError('Invalid page range');
      }
      const item = await record(url);
      const blob = item.blobs[page];
      if (!(blob instanceof Blob) || offset > blob.size) throw new RangeError('Page or offset unavailable');
      const bytes = new Uint8Array(await readBlob(blob.slice(offset, offset + count)));
      let binary = '';
      for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
      return { data: btoa(binary), nextOffset: offset + bytes.length, done: offset + bytes.length === blob.size };
    }
    return Object.freeze({descriptor, readChunk});
  }
  return Object.freeze({create, MAX_CHUNK_BYTES});
});
