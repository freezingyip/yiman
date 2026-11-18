(function (root) {
  'use strict';
  root.hengduDeliverNativeBatch = function (events, next) {
    let progress = !events.length, chapter = !next;
    if (!progress) {
      try { progress = typeof root.hengduApplyNativeProgress === 'function' && root.hengduApplyNativeProgress(events) === true; } catch (_) {}
    }
    if (!chapter) {
      try { chapter = typeof root.hengduNativeNextChapter === 'function' && root.hengduNativeNextChapter(next) === true; } catch (_) {}
    }
    return {progress: progress, chapter: chapter};
  };
})(window);
