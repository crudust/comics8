cask "comics8" do
  version "__VERSION__"
  sha256 "__SHA256__"

  url "https://github.com/crudust/comics8/releases/download/v#{version}/Comics8-mac.zip"
  name "Comics8"
  desc "Modern Comic & Webtoon Reader for macOS and Windows"
  homepage "https://github.com/crudust/comics8"

  app "Comics8.app"

  postflight do
    system_command "xattr", args: ["-cr", "#{appdir}/Comics8.app"]
  end

  zap trash: [
    "~/Library/Application Support/Comics8",
    "~/Library/Preferences/com.comics8.desktop.plist",
    "~/Library/Caches/com.comics8.desktop",
  ]
end
