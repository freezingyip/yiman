/* 18h public pages; read-only parsing, no source scripts executed. */
window.C18h=(()=>{
 const origin='https://18h.mm-cg.com';const proxy=u=>'/proxy?url='+encodeURIComponent(u);
 function valid(raw){let u=new URL(raw,origin);if(u.protocol!=='https:'||u.hostname!=='18h.mm-cg.com'||!/^\/(?:[\w-]+\.html|zh\/[\w-]+\/\d+\/content\.html)$/.test(u.pathname))throw Error('18h 網址無效');return u.href}
 function image(raw){if(!raw)return '';try{let u=new URL(raw,origin);if(u.protocol!=='https:'||!/(^|\.)(imgstream2\.com|imgstream3\.com)$/.test(u.hostname))return '';return proxy(u.href)}catch(e){return ''}}
 async function get(u){let r=await fetch(proxy(u));if(!r.ok)throw Error('無法連線 18h 漫');return r.text()}
 const doc=s=>new DOMParser().parseFromString(s,'text/html');
 async function search(q){let d=doc(await get(origin+'/zh/doujin_search/all/'+encodeURIComponent(q)+'/1.html')),seen=new Set(),results=[];for(let post of d.querySelectorAll('div.post')){let a=post.querySelector('a[href]'),title=post.querySelector('h3'),img=post.querySelector('img');if(!a||!title)continue;let url;try{url=valid(a.getAttribute('href'))}catch(e){continue}if(seen.has(url))continue;seen.add(url);results.push({title:title.textContent.trim(),url,cover:image(img&&img.getAttribute('src')),source:'18h漫！'})}return {results}}
 async function chapters(raw){let url=valid(raw);return {chapters:[{title:'全一話',url}]}}
 async function chapter(raw){let url=valid(raw),html=await get(url),images=[];let re=/Large_cgurl\[\d+\]\s*=\s*"([^"\r\n]+)"\s*;/g,m;while((m=re.exec(html))){let u=image(m[1]);if(!u)throw Error('來源圖片網址無效。');images.push(u)}if(!images.length)throw Error('此章節未提供公開圖片或需要登入。');let d=doc(html);return {title:(d.title||'18h 漫').trim(),images,nextChapter:null}}
 return {search,chapters,chapter};
})();
