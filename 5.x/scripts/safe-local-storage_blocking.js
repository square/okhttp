/*
 * Copyright 2014-2025 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
/** When Dokka is viewed via iframe, local storage could be inaccessible (see https://github.com/Kotlin/dokka/issues/3323)
 * This is a wrapper around local storage to prevent errors in such cases
 * */
const safeLocalStorage = (() => {
  let isLocalStorageAvailable = false;
  try {
    const testKey = '__testLocalStorageKey__';
    localStorage.setItem(testKey, testKey);
    localStorage.removeItem(testKey);
    isLocalStorageAvailable = true;
  } catch (e) {
    console.error('Local storage is not available', e);
  }

  return {
    getItem: (key) => {
      if (!isLocalStorageAvailable) {
        return null;
      }
      return localStorage.getItem(key);
    },
    setItem: (key, value) => {
      if (!isLocalStorageAvailable) {
        return;
      }
      localStorage.setItem(key, value);
    },
    removeItem: (key) => {
      if (!isLocalStorageAvailable) {
        return;
      }
      localStorage.removeItem(key);
    },
    getKeys: () => {
      if (!isLocalStorageAvailable) {
        return [];
      }
      return Object.keys(localStorage);
    },
  };
})();

/** When Dokka is viewed via iframe, session storage could be inaccessible (see https://github.com/Kotlin/dokka/issues/3323)
 * This is a wrapper around session storage to prevent errors in such cases
 * */
const safeSessionStorage = (() => {
  let isSessionStorageAvailable = false;
  try {
    const testKey = '__testSessionStorageKey__';
    sessionStorage.setItem(testKey, testKey);
    sessionStorage.removeItem(testKey);
    isSessionStorageAvailable = true;
  } catch (e) {
    console.error('Session storage is not available', e);
  }

  return {
    getItem: (key) => {
      if (!isSessionStorageAvailable) {
        return null;
      }
      return sessionStorage.getItem(key);
    },
    setItem: (key, value) => {
      if (!isSessionStorageAvailable) {
        return;
      }
      sessionStorage.setItem(key, value);
    },
    removeItem: (key) => {
      if (!isSessionStorageAvailable) {
        return;
      }
      sessionStorage.removeItem(key);
    },
    getKeys: () => {
      if (!isSessionStorageAvailable) {
        return [];
      }
      return Object.keys(sessionStorage);
    },
  };
})();
