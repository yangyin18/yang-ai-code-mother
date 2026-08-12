# nginx 网站部署

生成的应用由 **nginx** 作为 Web 服务器对外提供服务，后端（Spring）只负责生成、部署与列表管理。

## 部署布局

```
{web-root}/{deployKey}/index.html
{web-root}/{deployKey}/style.css
{web-root}/{deployKey}/script.js
```

- `web-root`：后端配置 `code.deploy.web-root` 指向的 nginx 站点根目录，
  本机为 `E:\software\nginx-1.28.3\html\apps`（注意：须与 `nginx.conf` 的 `root html` 配合，
  相对 nginx 安装目录解析）。
- `deployKey`：部署标识，首次部署随机生成 8 位串，重复部署复用（访问地址保持稳定）。

## 访问地址

默认 nginx.conf（`listen 80; server_name localhost; location / { root html; index index.html index.htm; }`）
无需改动即可生效：

```
http://localhost/apps/{deployKey}/
```

地址由后端按 `code.deploy.base-url`（本机 `http://localhost/apps`）拼出，部署接口
`POST /app/deploy` 会直接返回该地址；应用详情 / 精选列表的 `deployUrl` 字段也可直接跳转。

## 常用命令

在 nginx 安装目录（`E:\software\nginx-1.28.3`）执行：

```bat
:: 启动
start nginx.exe

:: 重载配置（改 conf 后生效）
nginx -s reload

:: 平滑停止
nginx -s quit

:: 强制停止
nginx -s stop
```

## 说明

- 若要把部署站点换到别的端口 / 路径，修改 `code.deploy.web-root`、`code.deploy.base-url`，
  并在 `nginx.conf` 里调整对应的 `root` / `location`，两者保持一致即可。
- 生成的代码由 `CodeSaver` 写入 `code.deploy.source-root`（默认 `{user.dir}/tmp/code_output`），
  部署时会把该目录下的文件拷贝到 `{web-root}/{deployKey}/`，覆盖式发布。
