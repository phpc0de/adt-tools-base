<#import "root://gradle-projects/NewAndroidModule/root/shared_macros.ftl" as shared>
<#import "root://activities/common/kotlin_macros.ftl" as kt>

apply plugin: 'com.android.library'
<@kt.addKotlinPlugins />

<@shared.androidConfig hasApplicationId=false applicationId='' isBaseFeature=false hasTests=true canHaveCpp=true canUseProguard=false />

dependencies {
    <@kt.addKotlinDependencies />
<#if useAndroidX>
    ${getConfigurationName("compile")} 'androidx.media:media:+'
<#else>
    ${getConfigurationName("compile")} 'com.android.support:support-media-compat:+'
</#if>
}