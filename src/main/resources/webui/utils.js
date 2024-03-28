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

// Post リクエスト (formData)
// json を return する
function post(url, formData) {
    return new Promise((resolve) => {
        fetch(url, {
            method: "POST",
            body: formData
        })
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
    const url = "https://hm-nrm.h3z.jp/uploader/work.php"; // アップロード先のURL
    const formData = new FormData();
    formData.append("files", file);
    const json = await post(url, formData);
    if (json == undefined) return "アップロードに失敗しました"
    else if (json.files[0].error != undefined) return json.files[0].error;
    return json.files[0].url;
}