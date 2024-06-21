// 入力した値を Minecraft の Mod に送信する
async function toMC() {

    // HTMLから 画像アドレス を取得
    let address = document.getElementById("address").value;

    // もし URL の長さが 30 より大きい場合
    if (address.length > 30) {
        // 短縮URL を取得して address に代入する
        address = await getShortURL(address);
    }

    //---------- HTMLから値を取得 ---------- start
    const width = document.getElementById("width").value;
    const height = document.getElementById("height").value;
    const x = document.getElementById("x").value;
    const y = document.getElementById("y").value;
    const z = document.getElementById("z").value;
    const rx = document.getElementById("rx").value;
    const ry = document.getElementById("ry").value;
    const rz = document.getElementById("rz").value;
    //---------- HTMLから値を取得 ---------- end

    const reqJson = {
        "address": address,
        "width": width,
        "height": height,
        "x": x,
        "y": y,
        "z": z,
        "rx": rx,
        "ry": ry,
        "rz": rz
    }

    // Minecraft に 看板に記述される値を Post で送る
    const resJson = await postJson("./give", reqJson);

    // レスポンス Json をコンソールに出力
    console.log(resJson);

    // もし、成功した場合は このWebUIを閉じる
    if (resJson.status) window.close();
    // そうでない場合は コンソールに エラーメッセージを送信
    else console.log("The give command was not successful.");
}

// アップロードする画像ファイルが選択されたら
async function onFile(input) {
    // 画像ファイルを取得
    const file = input.files[0];
    // アップロードして画像のURLを取得
    const url = await uploadFile(file);
    // 画像アドレスのテキストボックスに画像のURLを入力
    document.getElementById("address").value = url;

    // プレビュー画像を更新する
    detectChangeURL();
}