package verify;

import org.pf4j.ExtensionPoint;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.PluginDescriptor;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 离线校验 spmod 包：PF4J 描述符解析、扩展类可加载、类路径完整。
 */
public final class VerifySpmod {

    public static void main(String[] args) throws Exception {
        Path spmod = Path.of(args[0]);
        Path tmp = Files.createTempDirectory("spmod-verify");
        try (InputStream in = Files.newInputStream(spmod);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = tmp.resolve(entry.getName()).normalize();
                if (!out.startsWith(tmp)) {
                    throw new IllegalStateException("非法条目: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        System.out.println("== 解压到 " + tmp);
        // 官方 spmod 布局：根 META-INF/MANIFEST.MF 只有版本，真正的 PF4J 清单在 classes/META-INF/ 下
        Path classesDir = tmp.resolve("classes");
        Path root = Files.isDirectory(classesDir) ? classesDir : tmp;
        System.out.println("== 插件根 = " + root.getFileName());
        ManifestPluginDescriptorFinder finder = new ManifestPluginDescriptorFinder();
        if (!finder.isApplicable(root)) {
            throw new IllegalStateException("描述符不可用：未找到 META-INF/MANIFEST.MF");
        }
        PluginDescriptor d = finder.find(root);
        System.out.println("pluginId    = " + d.getPluginId());
        System.out.println("pluginClass = " + d.getPluginClass());
        System.out.println("version     = " + d.getVersion());
        System.out.println("provider    = " + d.getProvider());
        System.out.println("description = " + d.getPluginDescription());
        System.out.println("deps        = " + d.getDependencies());
        System.out.println("requires    = " + d.getRequires());

        // 扩展索引
        Path idx = root.resolve("META-INF/extensions.idx");
        if (!Files.exists(idx)) {
            throw new IllegalStateException("缺少 META-INF/extensions.idx");
        }
        List<String> extensions = Files.readAllLines(idx).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        System.out.println("extensions  = " + extensions);

        // 类加载：classes/ + lib/*.jar + 校验进程自身的 classpath（含 API jar）
        List<URL> urls = new java.util.ArrayList<>();
        urls.add(root.toUri().toURL());
        Path lib = tmp.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var s = Files.list(lib)) {
                for (Path jar : s.toList()) {
                    urls.add(jar.toUri().toURL());
                    System.out.println("lib         = " + jar.getFileName());
                }
            }
        }
        URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]),
                VerifySpmod.class.getClassLoader());

        Class<?> pluginClass = Class.forName(d.getPluginClass(), false, cl);
        System.out.println("Plugin-Class 可加载: " + pluginClass.getName()
                + " 是PF4J Plugin=" + org.pf4j.Plugin.class.isAssignableFrom(pluginClass));

        for (String ext : extensions) {
            Class<?> c = Class.forName(ext, false, cl);
            boolean ep = ExtensionPoint.class.isAssignableFrom(c);
            System.out.println("扩展可加载: " + c.getName() + " 实现ExtensionPoint=" + ep);
            if (!ep) {
                throw new IllegalStateException(ext + " 未实现 ExtensionPoint");
            }
            // 触发方法解析，尽早暴露签名不匹配
            for (var m : c.getMethods()) {
                if (m.getDeclaringClass() == c) {
                    m.toString();
                }
            }
        }

        System.out.println("校验通过");
        cl.close();
    }
}
