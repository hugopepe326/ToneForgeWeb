// Pure-JS IndexedDB persistence layer for ToneForge Web patches
(function (global) {
  'use strict';

  const DB_NAME = 'ToneForgeWebDB';
  const DB_VERSION = 1;
  const STORE_PATCHES = 'patches';

  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = (e) => {
        const db = e.target.result;
        if (!db.objectStoreNames.contains(STORE_PATCHES)) {
          const store = db.createObjectStore(STORE_PATCHES, { keyPath: 'id', autoIncrement: true });
          store.createIndex('createdAt', 'createdAt', { unique: false });
          store.createIndex('category', 'category', { unique: false });
        }
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error || new Error('No se pudo abrir IndexedDB'));
    });
  }

  async function getAllPatches() {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_PATCHES, 'readonly');
      const store = tx.objectStore(STORE_PATCHES);
      const req = store.getAll();
      req.onsuccess = () => {
        const rows = Array.isArray(req.result) ? req.result : [];
        rows.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
        resolve(rows);
      };
      req.onerror = () => reject(req.error);
    });
  }

  async function insertPatch(patch) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_PATCHES, 'readwrite');
      const store = tx.objectStore(STORE_PATCHES);
      const record = { ...patch, createdAt: patch.createdAt || Date.now() };
      delete record.id;
      const req = store.add(record);
      req.onsuccess = () => resolve({ ...record, id: req.result });
      req.onerror = () => reject(req.error);
    });
  }

  async function updatePatch(patch) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_PATCHES, 'readwrite');
      const store = tx.objectStore(STORE_PATCHES);
      const req = store.put(patch);
      req.onsuccess = () => resolve(patch);
      req.onerror = () => reject(req.error);
    });
  }

  async function deletePatch(id) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_PATCHES, 'readwrite');
      const store = tx.objectStore(STORE_PATCHES);
      const req = store.delete(id);
      req.onsuccess = () => resolve(true);
      req.onerror = () => reject(req.error);
    });
  }

  global.ToneDb = {
    getAllPatches,
    insertPatch,
    updatePatch,
    deletePatch
  };
})(window);
