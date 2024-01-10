package de.robv.android.xposed.installer.repo;

import android.util.Pair;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

//module就是xposed框架的应用
public class Module {
	public final Repository repository;
	public final List<Pair<String, String>> moreInfo = new LinkedList<Pair<String, String>>();
	//这个是module字段_id中对应所有的moduleversion（mversion中module_id为当前字段的_id）
	public final List<ModuleVersion> versions = new ArrayList<ModuleVersion>();
	public final List<String> screenshots = new ArrayList<String>();
	public String packageName;
	public String name;
	public String summary;
	public String description;
	public boolean descriptionIsHtml = false;
	public String author;
	public String support;
	public long created = -1;
	public long updated = -1;

	/* package */ Module(Repository repository) {
		this.repository = repository;
	}
}
