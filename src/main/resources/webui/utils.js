// Get リクエスト
// json を return する
function get(url) {
    return new Promise((resolve) => {
        fetch(url)
            .then((res) => res.json())
            .then((json) => resolve(json))
            .catch(() => resolve(undefined));
    });
}

// Post リクエスト (Json)
// json を return する
function postJson(url, data) {
    return new Promise((resolve) => {
        fetch(url, {
            method: "POST",
            headers: {// JSON形式のデータのヘッダー
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(data)
        })
            .then((res) => res.json())
            .then((json) => resolve(json))
            .catch(() => resolve(undefined));
    });
}

async function getShortURL(url) {
    let shorturl = localStorage.getItem(url);

    // ローカルストレージに短縮したURLがキャッシュされていたら
    // それを return する
    if (shorturl) return shorturl;

    // https://is.gd/ の URL短縮APIを利用して 短縮URLを生成
    json = await get(
        `https://is.gd/create.php?format=json&url=${encodeURIComponent(url)}`
    );
    // 送られてきた json に shorturl があるかどうか チェック

    // shorturl の値を置き換える
    shorturl = json.shorturl;

    if (shorturl) {
        //もし あったら

        // ローカルストレージを利用して 短縮したURLをキャッシュ
        localStorage.setItem(url, shorturl);

        // ログを送信
        console.log(`URLを短縮しました: ${url} -> ${shorturl}`);
        return shorturl;
    } else {
        //もし なかったら
        //エラーのログを送信
        console.error("URLを短縮できませんでした");
        return url;
    }
}

// ファイルをアップロードする関数
async function uploadFile(file) {
    const url = "./upload"; // アップロード先のURL
    const json = { file: await toBase64(file), name: file.name };
    const resJson = await postJson(url, json);
    if (resJson == undefined || !resJson.status) return "アップロードに失敗しました"
    return resJson.result;
}

// base64 変換
function toBase64(file) {
    const reader = new FileReader();
    return new Promise((resolve) => {
        reader.onload = (e) => resolve(e.target.result);
        reader.readAsDataURL(file);
    });
}